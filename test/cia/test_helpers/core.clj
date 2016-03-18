(ns cia.test-helpers.core
  (:refer-clojure :exclude [get])
  (:require [cia.auth :as auth]
            [cia.auth.allow-all :as aa]
            [cia.properties :as props]
            [cia.store :as store]
            [cia.stores.memory.actor :as ma]
            [cia.stores.memory.campaign :as mca]
            [cia.stores.memory.coa :as mco]
            [cia.stores.memory.exploit-target :as me]
            [cia.stores.memory.feedback :as mf]
            [cia.stores.memory.identity :as mi]
            [cia.stores.memory.incident :as mic]
            [cia.stores.memory.indicator :as min]
            [cia.stores.memory.judgement :as mj]
            [cia.stores.memory.sighting :as ms]
            [cia.stores.memory.ttp :as mt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.data :as cd]
            [clojure.edn :as edn]
            [clojure.test :as ct]
            [ring.adapter.jetty :as jetty]
            [schema.core :as schema]))

(defmethod ct/assert-expr 'deep= [msg form]
  (let [a (second form)
        b (nth form 2)]
    `(let [[only-a# only-b# _] (cd/diff ~a ~b)]
       (if (or only-a# only-b#)
         (let [only-msg# (str (when only-a# (str "Only in A: " only-a#))
                              (when (and only-a# only-b#) ", ")
                              (when only-b# (str "Only in B: " only-b#)))]
           (ct/do-report {:type :fail, :message ~msg,
                          :expected '~form, :actual only-msg#}))
         (ct/do-report {:type :pass, :message ~msg,
                        :expected '~form, :actual nil})))))

(defn fixture-properties [f]
  (props/init! "cia-test.properties")
  (f))


(defn fixture-schema-validation [f]
  (schema/with-fn-validation
    (f)))

(defn fixture-allow-all-auth [f]
  (let [orig-auth-srvc @auth/auth-service]
    (reset! auth/auth-service (aa/->AuthService))
    (f)
    (reset! auth/auth-service orig-auth-srvc)))

(defn init-atom [f]
  (fn []
    (f (atom {}))))

(def memory-stores
  {store/actor-store          (init-atom ma/->ActorStore)
   store/judgement-store      (init-atom mj/->JudgementStore)
   store/feedback-store       (init-atom mf/->FeedbackStore)
   store/campaign-store       (init-atom mca/->CampaignStore)
   store/coa-store            (init-atom mco/->COAStore)
   store/exploit-target-store (init-atom me/->ExploitTargetStore)
   store/incident-store       (init-atom mic/->IncidentStore)
   store/indicator-store      (init-atom min/->IndicatorStore)
   store/sighting-store       (init-atom ms/->SightingStore)
   store/ttp-store            (init-atom mt/->TTPStore)
   store/identity-store       (init-atom mi/->IdentityStore)})

(defn fixture-store [store-map]
  (fn [f]
    (doseq [[store impl-fn] store-map]
      (reset! store (impl-fn)))
    (f)
    (doseq  [store (keys store-map)]
      (reset! store nil))))

(def fixture-in-memory-store (fixture-store memory-stores))

(def http-port 3000)

(defn fixture-server [app & {:keys [port]
                             :or {port http-port}}]
  (fn [f]
    (let [server (jetty/run-jetty app
                                  {:host "localhost"
                                   :port port
                                   :join? false})]
      (f)
      (.stop server))))

(defn set-capabilities! [login role caps]
  (store/create-identity @store/identity-store
                         {:login login
                          :role role
                          :capabilities caps}))

(defn url
  ([path]
   (url path http-port))
  ([path port]
   (format "http://localhost:%d/%s" port path)))

;; Replace this with clojure.string/includes? once we are at Clojure 1.8
(defn includes?
  [^CharSequence s ^CharSequence substr]
  (.contains (.toString s) substr))

(defn content-type? [expected-str]
  (fn [test-str]
    (if (some? test-str)
      (includes? (name test-str) expected-str)
      false)))

(def json? (content-type? "json"))

(def edn? (content-type? "edn"))

(defn parse-body
  ([http-response]
   (parse-body http-response nil))
  ([{{content-type "Content-Type"} :headers
     body :body}
    default]
   (cond
     (edn? content-type) (edn/read-string body)
     (json? content-type) (json/parse-string body)
     :else default)))

(defn encode-body
  [body content-type]
  (cond
    (edn? content-type) (pr-str body)
    (json? content-type) (json/generate-string body)
    :else body))

(defn get [path & {:as options}]
  (let [options
        (merge {:accept :edn
                :throw-exceptions false}
               options)

        response
        (http/get (url path)
                  options)]
    (assoc response :parsed-body (parse-body response))))

(defn post [path & {:as options}]
  (let [{:keys [body content-type]
         :as options}
        (merge {:content-type :edn
                :accept :edn
                :throw-exceptions false
                :socket-timeout 2000
                :conn-timeout 2000}
               options)

        response
        (http/post (url path)
                   (-> options
                       (cond-> body (assoc :body (encode-body body content-type)))))]
    (assoc response :parsed-body (parse-body response))))

(defn delete [path & {:as options}]
  (http/delete (url path)
               (merge {:throw-exceptions false}
                      options)))

(defn put [path & {:as options}]
  (let [{:keys [body content-type]
         :as options}
        (merge {:content-type :edn
                :accept :edn
                :throw-exceptions false
                :socket-timeout 2000
                :conn-timeout 2000}
               options)

        response
        (http/put (url path)
                  (-> options
                      (cond-> body (assoc :body (encode-body body content-type)))))]
    (assoc response :parsed-body (parse-body response))))

(defmacro deftest-for-each-fixture [test-name fixture-map & body]
  `(do
     ~@(for [[name-key fixture-fn] fixture-map]
         `(clojure.test/deftest ~(with-meta (symbol (str test-name "-" (name name-key)))
                                   {(keyword test-name) true})
            (~fixture-fn (fn [] ~@body))))))