(ns datascript.bench.datascript
  (:require
   [datascript.core :as d]
   [datascript.pull-api-v2 :as pull-api-v2]
   [datascript.pull-api-v3 :as pull-api-v3]
   [datascript.bench.bench :as bench]
   #?(:clj [jsonista.core :as jsonista])))

#?(:cljs (enable-console-print!))

(def schema
  {:id      {:db/unique :db.unique/identity}
   :follows {:db/valueType   :db.type/ref
             :db/cardinality :db.cardinality/many}
   :alias   {:db/cardinality :db.cardinality/many}})

(def empty-db (d/empty-db schema))

(def *db100k
  (delay
    (d/db-with (d/empty-db schema) @bench/*people20k)))

(defn wide-db [depth width]
  (d/db-with empty-db (bench/wide-db 1 depth width)))

(defn long-db [depth width]
  (d/db-with empty-db (bench/long-db depth width)))

(defn bench-add-1 []
  (bench/bench "add-1"
    (reduce
      (fn [db p]
        (-> db
          (d/db-with [[:db/add (:db/id p) :name      (:name p)]])
          (d/db-with [[:db/add (:db/id p) :last-name (:last-name p)]])
          (d/db-with [[:db/add (:db/id p) :sex       (:sex p)]])
          (d/db-with [[:db/add (:db/id p) :age       (:age p)]])
          (d/db-with [[:db/add (:db/id p) :salary    (:salary p)]])))
      empty-db
      @bench/*people20k)))

(defn bench-add-5 []
  (bench/bench "add-5"
    (reduce
      (fn [db p]
        (d/db-with db [p]))
      empty-db
      @bench/*people20k)))

(defn bench-add-all []
  (bench/bench "add-all"
    (d/db-with
      empty-db
      @bench/*people20k)))

(defn bench-init []
  (let [datoms (into []
                 (for [p @bench/*people20k
                       :let [id (#?(:clj Integer/parseInt :cljs js/parseInt) (:db/id p))]
                       [k v] p
                       :when (not= k :db/id)]
                   (d/datom id k v)))]
    (bench/bench "init"
      (d/init-db datoms))))

(defn bench-retract-5 []
  (let [db   (d/db-with empty-db @bench/*people20k)
        eids (->> (d/datoms db :aevt :name) (map :e) (shuffle))]
    (bench/bench "retract-5"
      (reduce (fn [db eid] (d/db-with db [[:db.fn/retractEntity eid]])) db eids))))

(defn bench-q1 []
  (bench/bench "q1"
    (d/q '[:find ?e
           :where [?e :name "Ivan"]]
      @*db100k)))

(defn bench-q2 []
  (bench/bench "q2"
    (d/q '[:find ?e ?a
           :where [?e :name "Ivan"]
                  [?e :age ?a]]
      @*db100k)))

(defn bench-q3 []
  (bench/bench "q3"
    (d/q '[:find ?e ?a
           :where [?e :name "Ivan"]
                  [?e :age ?a]
                  [?e :sex :male]]
      @*db100k)))

(defn bench-q4 []
  (bench/bench "q4"
    (d/q '[:find ?e ?l ?a
           :where [?e :name "Ivan"]
                  [?e :last-name ?l]
                  [?e :age ?a]
                  [?e :sex :male]]
      @*db100k)))

(defn bench-qpred1 []
  (bench/bench "qpred1"
    (d/q '[:find ?e ?s
           :where [?e :salary ?s]
                  [(> ?s 50000)]]
      @*db100k)))

(defn bench-qpred2 []
  (bench/bench "qpred2"
    (d/q '[:find ?e ?s
           :in   $ ?min_s
           :where [?e :salary ?s]
                  [(> ?s ?min_s)]]
      @*db100k 50000)))

(def *pull-db
  (delay
    (wide-db 4 5)))

(defn bench-pull []
  (bench/bench "pull"
    (datascript.pull-api/pull @*pull-db [:db/id :last-name :alias :sex :age :salary {:follows '...}] [:id 1])))

(defn bench-pull-entities []
  (let [f (fn f [entity]
            (assoc
              (select-keys entity [:db/id :last-name :alias :sex :age :salary])
              :follows (mapv f (:follows entity))))]
    (bench/bench "pull-entities"
      (f (d/entity @*pull-db [:id 1])))))

(defn bench-pull-v2 []
  (bench/bench "pull-v2"
    (pull-api-v2/pull @*pull-db [:db/id :last-name :alias :sex :age :salary {:follows '...}] [:id 1])))

(defn bench-pull-v3 []
  (bench/bench "pull-v3"
    (pull-api-v3/pull @*pull-db [:db/id :last-name :alias :sex :age :salary {:follows '...}] 1)))

(defn bench-pull-v3-* []
  (bench/bench "pull-v3-*"
    (pull-api-v3/pull @*pull-db ['* {:follows '...}] 1)))

(comment
  (clojure.pprint/pprint
  (let [db (wide-db 2 2)]
    (clojure.data/diff 
      (pull-api-v3/pull db [:db/id :last-name :alias :sex :age :salary {:follows '...}] 1)
      (datascript.pull-api/pull db [:db/id :last-name :alias :sex :age :salary {:follows '...}] 1)
      )))
  (=
    (pull-api-v3/pull @*pull-db [:db/id :last-name :alias :sex :age :salary {:follows '...}] 1)
    (datascript.pull-api/pull @*pull-db [:db/id :last-name :alias :sex :age :salary {:follows '...}] 1))
  )

(defn bench-rules [db]
  (d/q '[:find ?e ?e2
         :in   $ %
         :where (follows ?e ?e2)]
       db
       '[[(follows ?x ?y)
          [?x :follows ?y]]
         [(follows ?x ?y)
          [?x :follows ?t]
          (follows ?t ?y)]]))

(defn bench-rules-wide-3x3 []
  (let [db (wide-db 3 3)]
    (bench/bench "rules-wide-3x3" (bench-rules db))))

(defn bench-rules-wide-5x3 []
  (let [db (wide-db 5 3)]
    (bench/bench "rules-wide-5x3" (bench-rules db))))

(defn bench-rules-wide-7x3 []
  (let [db (wide-db 7 3)]
    (bench/bench "rules-wide-7x3" (bench-rules db))))

(defn bench-rules-wide-4x6 []
  (let [db (wide-db 4 6)]
    (bench/bench "rules-wide-4x6" (bench-rules db))))

(defn bench-rules-long-10x3 []
  (let [db (long-db 10 3)]
    (bench/bench "rules-long-10x3" (bench-rules db))))

(defn bench-rules-long-30x3 []
  (let [db (long-db 30 3)]
    (bench/bench "rules-long-30x3" (bench-rules db))))

(defn bench-rules-long-30x5 []
  (let [db (long-db 30 5)]
    (bench/bench "rules-long-30x5" (bench-rules db))))

(def *serialize-db 
  (delay
    (d/db-with empty-db
      (take 300000 bench/people))))

#?(:clj
   (def mapper
     (com.fasterxml.jackson.databind.ObjectMapper.)))

(defn bench-freeze []
  (bench/bench
    (-> @*serialize-db (d/serializable) #?(:clj (jsonista/write-value-as-string mapper) :cljs js/JSON.stringify))))

(defn bench-thaw []
  (let [json (-> @*serialize-db (d/serializable) #?(:clj (jsonista/write-value-as-string mapper) :cljs js/JSON.stringify))]
    (bench/bench
      (-> json #?(:clj (jsonista/read-value mapper) :cljs js/JSON.parse) d/from-serializable))))

(def benches
  {"add-1"           bench-add-1
   "add-5"           bench-add-5
   "add-all"         bench-add-all
   "init"            bench-init
   "retract-5"       bench-retract-5
   "q1"              bench-q1
   "q2"              bench-q2
   "q3"              bench-q3
   "q4"              bench-q4
   "qpred1"          bench-qpred1
   "qpred2"          bench-qpred2
   "pull"            bench-pull
   "pull-entities"   bench-pull-entities
   "pull-v2"         bench-pull-v2
   "pull-v3"         bench-pull-v3
   "rules-wide-3x3"  bench-rules-wide-3x3
   "rules-wide-5x3"  bench-rules-wide-5x3
   "rules-wide-7x3"  bench-rules-wide-7x3
   "rules-wide-4x6"  bench-rules-wide-4x6
   "rules-long-10x3" bench-rules-long-10x3
   "rules-long-30x3" bench-rules-long-30x3
   "rules-long-30x5" bench-rules-long-30x5
   "freeze"          bench-freeze
   "thaw"            bench-thaw})

(defn ^:export -main
  "clj -A:bench -M -m datascript.bench.datascript [--profile] (add-1 | add-5 | ...)*"
  [& args]
  (let [profile? (.contains (or args ()) "--profile")
        args     (remove #{"--profile"} args)
        names    (or (not-empty args) (sort (keys benches)))
        _        (apply println #?(:clj "CLJ:" :cljs "CLJS:") names)
        longest  (last (sort-by count names))]
    (binding [bench/*profile* profile?]
      (doseq [name names
              :let [fn (benches name)]]
        (if (nil? fn)
          (println "Unknown benchmark:" name)
          (let [{:keys [mean-ms file]} (fn)]
            (println
              (bench/right-pad name (count longest))
              " "
              (bench/left-pad (bench/round mean-ms) 6) "ms/op"
              " " (or file ""))))))
    #?(:clj (shutdown-agents))))

(comment
  (-main "pull" "pull-entities" "pull-v2")
  (-main "add-1" "pull-entities" "q4")

  (require 'datascript.bench.datascript :reload-all)

  (bench-add-1)
  (bench-add-5)
  (bench-add-all)
  (bench-init)
  (bench-retract-5)
  (bench-q1)
  (bench-q2)
  (bench-q3)
  (bench-q4)
  (bench-qpred1)
  (bench-qpred2)
  (bench-pull)
  (bench-pull-entities)
  (bench-pull-v2)
  (bench-pull-v3)
  (binding [bench/*profile* true]
    (bench-pull-v3))
  (bench-pull-v3-*)
  (binding [bench/*profile* true]
    (bench-pull-v3-*))
  (bench-rules-wide-3x3)
  (bench-rules-wide-5x3)
  (bench-rules-wide-7x3)
  (bench-rules-wide-4x6)
  (bench-rules-long-10x3)
  (bench-rules-long-30x3)
  (bench-rules-long-30x5)
  (bench-freeze)
  (bench-thaw))