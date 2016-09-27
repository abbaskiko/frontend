(ns frontend.core
  (:require [cljs.core.async :as async :refer [chan]]
            [clojure.zip :as zip]
            [compassus.core :as compassus]
            [figwheel.client.utils :as figwheel-utils]
            [frontend.analytics.core :as analytics]
            [frontend.api :as api]
            [frontend.async :refer [put!]]
            [frontend.browser-settings :as browser-settings]
            [frontend.components.app :as app]
            [frontend.components.app.legacy :as legacy]
            [frontend.config :as config]
            [frontend.controllers.api :as api-con]
            [frontend.controllers.controls :as controls-con]
            [frontend.controllers.errors :as errors-con]
            [frontend.controllers.navigation :as nav-con]
            [frontend.controllers.ws :as ws-con]
            [frontend.datetime :as datetime]
            [frontend.history :as history]
            [frontend.instrumentation :refer [wrap-api-instrumentation]]
            [frontend.parser :as parser]
            [frontend.pusher :as pusher]
            [frontend.routes :as routes]
            [frontend.send :as send]
            [frontend.state :as state]
            [frontend.support :as support]
            [frontend.timer :as timer]
            [frontend.utils :as utils :refer [mlog set-canonical!]]
            goog.dom
            [goog.events :as gevents]
            [om.core :as om :include-macros true]
            [om.next :as om-next]
            [schema.core :as s :include-macros true])
  (:require-macros
   [cljs.core.async.macros :as am :refer [alt! go]]
   [frontend.devtools :refer [require-devtools!]]
   [frontend.utils :refer [swallow-errors]]))

(when config/client-dev?
  (enable-console-print!)
  (require-devtools!)
  (s/set-fn-validation! true))


(defn initial-state
  "Builds the initial app state, including data that comes from the
  renderContext."
  []
  (assoc state/initial-state
         :current-user (-> js/window
                           (aget "renderContext")
                           (aget "current_user")
                           utils/js->clj-kw)
         :render-context (-> js/window
                             (aget "renderContext")
                             utils/js->clj-kw)))

(defn log-channels?
  "Log channels in development, can be overridden by the log-channels query param"
  []
  (:log-channels? utils/initial-query-map (config/log-channels?)))

(defn controls-handler
  [value state container comms]
  (when (log-channels?)
    (mlog "Controls Verbose: " value))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial controls-con/control-event container (first value) (second value)))
       (controls-con/post-control-event! container (first value) (second value) previous-state @state comms)))))

(defn nav-handler
  [[navigation-point {:keys [inner? query-params] :as args} :as value] state history comms]
  (when (log-channels?)
    (mlog "Navigation Verbose: " value))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial nav-con/navigated-to history navigation-point args))
       (nav-con/post-navigated-to! history navigation-point args previous-state @state comms)
       (set-canonical! (:_canonical args))
       (when-not (= navigation-point :navigate!)
         (analytics/track {:event-type :pageview
                           :navigation-point navigation-point
                           :current-state @state}))
       (when-let [app-dominant (goog.dom.getElementByClass "app-dominant")]
         (set! (.-scrollTop app-dominant) 0))
       (when-let [main-body (goog.dom.getElementByClass "main-body")]
         (set! (.-scrollTop main-body) 0))))))

(defn api-handler
  [value state container comms]
  (when (log-channels?)
    (mlog "API Verbose: " (first value) (second value) (utils/third value)))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           message (first value)
           status (second value)
           api-data (utils/third value)]
       (swap! state (wrap-api-instrumentation (partial api-con/api-event container message status api-data)
                                              api-data))
       (when-let [date-header (get-in api-data [:response-headers "Date"])]
         (datetime/update-server-offset date-header))
       (api-con/post-api-event! container message status api-data previous-state @state comms)))))

(defn ws-handler
  [value state pusher comms]
  (when (log-channels?)
    (mlog "websocket Verbose: " (pr-str (first value)) (second value) (utils/third value)))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial ws-con/ws-event pusher (first value) (second value)))
       (ws-con/post-ws-event! pusher (first value) (second value) previous-state @state comms)))))

(defn errors-handler
  [value state container comms]
  (when (log-channels?)
    (mlog "Errors Verbose: " value))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial errors-con/error container (first value) (second value)))
       (errors-con/post-error! container (first value) (second value) previous-state @state comms)))))

(defn find-top-level-node []
  (.-body js/document))

(defn find-app-container []
  (goog.dom/getElement "app"))

(defn subscribe-to-user-channel [user ws-ch]
  (put! ws-ch [:subscribe {:channel-name (pusher/user-channel user)
                           :messages [:refresh]}]))

(defonce application nil)

;; Wraps an atom, but only exposes the portion of its value at path.
(deftype LensedAtom [atom path]
  IDeref
  (-deref [_] (get-in (deref atom) path))

  ISwap
  (-swap! [_ f] (swap! atom update-in path f))
  (-swap! [_ f a] (swap! atom update-in path f a))
  (-swap! [_ f a b] (swap! atom update-in path f a b))
  (-swap! [_ f a b xs] (apply swap! atom update-in path f a b xs))

  IWatchable
  ;; We don't need to notify watches, because our parent atom does that.
  (-notify-watches [_ _ _] nil)
  (-add-watch [this key f]
    ;; "Namespace" the key in the parent's watches with this object.
    (add-watch atom [this key]
               (fn [[_ key] _ old-state new-state]
                 (f key this
                    (get-in old-state path)
                    (get-in new-state path))))
    this)
  (-remove-watch [this key]
    (remove-watch atom [this key])))

(defn- force-update-recursive
  ".forceUpdate root-component and every component within it.

  This function uses React internals and should only be used for development
  tasks such as code reloading."
  [root-component]
  (letfn [(js-vals [o]
            (map #(aget o %) (js-keys o)))
          ;; Finds the children of a React internal instance of a component.
          ;; That could be a single _renderedComponent or several
          ;; _renderedChildren.
          (children [ic]
            (or (some-> (.-_renderedComponent ic) vector)
                (js-vals (.-_renderedChildren ic))))
          (descendant-components [c]
            ;; Walk the tree finding tall of the descendent internal instances...
            (->> (tree-seq #(seq (children %)) children (.-_reactInternalInstance c))
                 ;; ...map to the public component instances...
                 (map #(.-_instance %))
                 ;; ...and remove the nils, which are from DOM nodes.
                 (remove nil?)))]
    (doseq [c (descendant-components root-component)]
      (.forceUpdate c))))

;; http://stackoverflow.com/a/15020649/42188
(defn- map-zipper [m]
  (zip/zipper
    (fn [x] (or (map? x) (map? (nth x 1))))
    (fn [x] (seq (if (map? x) x (nth x 1))))
    (fn [x children]
      (if (map? x)
        (into {} children)
        (assoc x 1 (into {} children))))
    m))

(defn- nils->maps
  "Turns {:a {:b nil}, :one {:two {:three \"3\"}}} into
  {:a {:b {}}, :one {:two {:three \"3\"}}}"
  [m]
  (loop [z (map-zipper m)]
    (cond
      (zip/end? z) (zip/root z)

      (and (implements? IMapEntry (zip/node z))
           (nil? (val (zip/node z))))
      (recur (zip/replace z [(key (zip/node z)) {}]))

      :else (recur (zip/next z)))))

(defn ^:export setup! []
  (support/enable-one!)
  (let [legacy-state (initial-state)
        comms {:controls (chan)
               :api (chan)
               :errors (chan)
               :nav (chan)
               :ws (chan)}
        top-level-node (find-top-level-node)
        container (find-app-container)
        history-imp (history/new-history-imp top-level-node)
        pusher-imp (pusher/new-pusher-instance (config/pusher))
        state-atom (atom {:legacy/state legacy-state
                          :app/current-user (when-let [rc-user (-> js/window
                                                                   (aget "renderContext")
                                                                   (aget "current_user"))]
                                              {:user/bitbucket-authorized (aget rc-user "bitbucket_authorized")})
                          :organization/by-vcs-type-and-name {}})

        ;; The legacy-state-atom is a LensedAtom which we can treat like a
        ;; normal atom but which presents only the legacy state.
        legacy-state-atom (LensedAtom. state-atom [:legacy/state])

        a (compassus/application
           {:routes app/routes
            :wrapper app/wrapper
            :reconciler-opts {:state state-atom
                              :normalize true
                              :parser parser/parser
                              :send send/send
                              :merge compassus/compassus-merge

                              ;; Workaround for
                              ;; https://github.com/omcljs/om/issues/781
                              :merge-tree #(utils/deep-merge %1 (nils->maps %2))

                              ;; Workaround for
                              ;; https://github.com/omcljs/om/issues/772 with
                              ;; solution merged in
                              ;; https://github.com/omcljs/om/pull/775. Should
                              ;; be able to remove this once we're on Om
                              ;; 1.0.0-alpha46 or greater.
                              :indexer (fn []
                                         (om-next/indexer
                                          {:index-component (fn [indexes component] indexes)
                                           :drop-component (fn [indexes component] indexes)
                                           :ref->components (fn [indexes k]
                                                              (transduce (map #(get-in indexes [:class->components %]))
                                                                         (completing into)
                                                                         (get-in indexes [:ref->components k] #{})
                                                                         (get-in indexes [:prop->classes k])))}))

                              :shared {:comms comms
                                       :timer-atom (timer/initialize)
                                       :track-event #(analytics/track (assoc % :current-state @legacy-state-atom))
                                       ;; Make the legacy-state-atom available to the legacy inputs system.
                                       :_app-state-do-not-use legacy-state-atom}}})]

    (set! application a)

    ;; Tell the parser which keys it should treat as pages.
    (doseq [[page-key _] app/routes]
      (parser/register-page-key! page-key))

    (browser-settings/setup! legacy-state-atom)

    (routes/define-routes! (:current-user legacy-state) application (:nav comms))

    (compassus/mount! application (goog.dom/getElement "app"))

    (when config/client-dev?
      ;; Re-render when Figwheel reloads.
      (gevents/listen js/document.body
                      "figwheel.js-reload"
                      #(force-update-recursive (om-next/app-root (compassus/get-reconciler a)))))

    (go
      (while true
        (alt!
          (:controls comms) ([v] (controls-handler v legacy-state-atom container comms))
          (:nav comms) ([v] (nav-handler v legacy-state-atom history-imp comms))
          (:api comms) ([v] (api-handler v legacy-state-atom container comms))
          (:ws comms) ([v] (ws-handler v legacy-state-atom pusher-imp comms))
          (:errors comms) ([v] (errors-handler v legacy-state-atom container comms)))))

    (when (config/enterprise?)
      (api/get-enterprise-site-status (:api comms)))

    (if-let [error-status (get-in legacy-state [:render-context :status])]
      ;; error codes from the server get passed as :status in the render-context
      (put! (:nav comms) [:error {:status error-status}])
      (routes/dispatch! (str "/" (.getToken history-imp))))
    (when-let [user (:current-user legacy-state)]
      (analytics/track {:event-type :init-user
                        :current-state legacy-state})
      (subscribe-to-user-channel user (:ws comms)))))


(defn ^:export toggle-admin []
  (swap! (om-next/app-state (compassus/get-reconciler application))
         update-in [:legacy/state :current-user :admin] not))

(defn ^:export toggle-dev-admin []
  (swap! (om-next/app-state (compassus/get-reconciler application))
         update-in [:legacy/state :current-user :dev-admin] not))

(defn ^:export explode []
  (swallow-errors
   (assoc [] :deliberate :exception)))


;; Figwheel offers an event when JS is reloaded but not when CSS is reloaded. A
;; PR is waiting to add this; until then fire that event from here.
;; See: https://github.com/bhauman/lein-figwheel/pull/463
(defn handle-css-reload [files]
  (figwheel-utils/dispatch-custom-event "figwheel.css-reload" files))
