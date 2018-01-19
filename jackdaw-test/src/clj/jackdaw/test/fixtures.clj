(ns jackdaw.test.fixtures
  "Test fixtures for kafka based apps"
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [jackdaw.client :as jc]
            [jackdaw.test.config :as config]
            [jackdaw.test.kafka :as broker]
            [jackdaw.test.kc :as kc]
            [jackdaw.test.fs :as fs]
            [jackdaw.test.zk :as zk])
  (:import [io.confluent.kafka.schemaregistry.rest SchemaRegistryConfig SchemaRegistryRestApplication]))

;; services

(defn zookeeper
  "A zookeeper test fixture

   Start up a zookeeper broker with the supplied config before running
   the test `t`"
  [config]
  (fn [t]
    (let [snapshot-dir (fs/tmp-dir "zookeeper-snapshot")
          log-dir  (fs/tmp-dir "zookeeper-log")
          _ (fs/delete-directories! snapshot-dir log-dir)
          zk (zk/start! {:config       config
                         :snapshot-dir snapshot-dir
                         :log-dir      log-dir})]
      (try
        (log/info "Started zookeeper fixture" zk)
        (t)
        (finally
          (log/info "Stopping zookeeper")
          (zk/stop! zk)
          (log/info "Stopped zookeeper fixture" zk))))))

(defn broker
  "A kafka test fixture.

   Start up a kafka broker with the supplied config before running the

   test `t`.

   If the optional `num-brokers` is provided, start up a cluster with that
   many brokers. Unfortunately there is a bit of a teardown cost to this
   as when you shutdown a broker, kafka tries to shuffle all it's data
   across to any remaining live brokers so use this with care. We've found
   that you don't really need this unless you're trying to test some weird
   edge case.

   Note that when using num-brokers > 1, you must explicitly set port and
   it must agree with the port implicit in `listeners` and/or
   `advertised.listeners`."
  ([config num-brokers]
   (fn [t]
     (let [multi-config (config/multi-config config)
           configs (if (= 1 num-brokers)
                     ;; no need to rewrite the config if we just have a single
                     ;; broker and the multi-config stuff can cause problems
                     ;; if you don't specify a port
                     [config]
                     (map multi-config (range num-brokers)))
           cluster (doall (map (fn [cfg]
                                 (fs/delete-directories! (get cfg "log.dirs"))
                                 (broker/start! {:config cfg}))
                               configs))]
       (try
         (log/info "Started " (if (> num-brokers 1) "multi" "single") "broker fixture" cluster)
         (t)
         (finally
           (log/info "Stopping kafka")
           ;; This takes a surprisingly
           (doseq [node cluster]
             (broker/stop! node))
           (log/info "Stopped multi-broker fixture" cluster))))))
  ([config]
   (broker config 1)))

(defn multi-broker
  "DEPRECATED: prefer use `broker` with the optional `num-brokers` argument"
  [config n]
  (broker config n))

(defn schema-registry
  [config]
  (fn [t]
    (let [app (SchemaRegistryRestApplication.
               (SchemaRegistryConfig. (jc/map->properties config)))
          server (.createServer app)]
      (try
        (.start server)
        (log/info "Started schema registry fixture" server)
        (t)
        (finally
          (.stop server)
          (log/info "Stopped schema registry fixture" server))))))

(defn kafka-connect
  [worker-config]
  (fn [t]

    (let [kc-runner (kc/start! {:config worker-config})]
      (try
        (log/info "Started Kafka Connector in standalone mode")
        (t)

        (finally
          (log/info "Shutting down kafka connect worker")
          (kc/stop! kc-runner))))))

;; fixture composition

(defn identity-fixture
  "They have this already in clojure.test but it is called
   `default-fixture` and it is private. Probably stu seirra's fault
   :troll:"
  [t]
  (t))
