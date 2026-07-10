#!/usr/bin/env bb
;; edn-datomize.bb — EDN → Datomic/Datascript tx-data 変換ツール。
;; com-etzhayyim-tsukuru 用に superproject 版 manifest/edn-datomize.bb (Phase 1)
;; を元に adapt: schema-path をこのリポジトリのルートに変更し、既に
;; 名前空間付き(:actor/* / :cell/* 等)のキーは再prefixしない
;; namespace-preserving モードを namespaced-key に組み込んだ(Phase 2 pilot 同様)。
;;
;; 「datomic/datascript query 可能」の定義: ファイルのトップレベルが
;; (d/transact conn (edn/read-string (slurp file))) にそのまま渡せる
;; tx-data ベクタ（entity-map のベクタ、各 map は :db/id を持つ）であること。
;;
;; マップ1個のファイルは [{...:db/id -1}] に包み、既存キーはファイル種別ごとの
;; 名前空間を付けた属性名にリネームする。値が Datomic の scalar valueType
;; （string/long/double/boolean/keyword、またはそれらの集合）に収まらないもの
;; （入れ子 map、map を含む vector 等）は pr-str した文字列として保持する
;; （valueType=string の "blob" 属性にする — トップレベルの entity+attribute
;;  粒度でのクエリは常に有効、blob の中身は呼び出し側で edn/read-string すれば
;;  読める）。属性定義は manifest/schema.edn に自動登録する（Datomic/Datascript
;; 両対応、:db.install/_attribute 等の Datomic 固有キーは使わない）。
;;
;; 使い方:
;;   bb manifest/edn-datomize.bb wrap-map <path> <ns>     — map 1個のファイルを変換
;;   bb manifest/edn-datomize.bb adr-dir  <dir>            — ADR frontmatter/body を変換
;;   bb manifest/edn-datomize.bb adr-file <path>           — ADR 1ファイルを変換

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn schema-path [] (io/file root "schema.edn"))

(defn slurp-edn [path] (edn/read-string (slurp path)))

(defn already-tx-data?
  "既に [{...:db/id ...} ...] 形式に変換済みか判定（再実行の冪等性用）。"
  [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

(defn classify
  "値から Datomic :db/valueType + :db/cardinality を推定する。scalar に収まらない
   値（入れ子 map / map を含む vector 等）は :blob true を返す(pr-str して string 化)。"
  [v]
  (cond
    (string? v)  {:type :db.type/string  :card :db.cardinality/one}
    (boolean? v) {:type :db.type/boolean :card :db.cardinality/one}
    (integer? v) {:type :db.type/long    :card :db.cardinality/one}
    (double? v)  {:type :db.type/double  :card :db.cardinality/one}
    (keyword? v) {:type :db.type/keyword :card :db.cardinality/one}
    (nil? v)     {:type :db.type/string  :card :db.cardinality/one}
    (and (coll? v) (empty? v))
    {:type :db.type/string :card :db.cardinality/many}
    (and (coll? v) (every? string? v))  {:type :db.type/string  :card :db.cardinality/many}
    (and (coll? v) (every? keyword? v)) {:type :db.type/keyword :card :db.cardinality/many}
    (and (coll? v) (every? integer? v)) {:type :db.type/long    :card :db.cardinality/many}
    :else {:type :db.type/string :card :db.cardinality/one :blob true}))

(defn attr-value [v]
  (let [{:keys [blob]} (classify v)]
    (if blob (pr-str v) v)))

(defn namespaced-key
  "既に名前空間付き(idiomatic Clojure style: :actor/id, :cell/id 等)のキーは
   そのまま保持し(re-prefix しない)、bare キーだけ ns-name で名前空間を付ける
   (namespace-preserving mode — Phase 2 pilot と同型)。"
  [ns-name k]
  (if (namespace k) k (keyword ns-name (name k))))

(defn entity-from-map
  "トップレベル map の各キーに ns-name の名前空間を付け、:db/id を足した 1 entity にする。"
  [content ns-name]
  (into {:db/id -1}
        (map (fn [[k v]] [(namespaced-key ns-name k) (attr-value v)]))
        content))

(defn schema-attrs
  [content ns-name]
  (for [[k v] content]
    (let [{:keys [type card]} (classify v)]
      {:db/ident (namespaced-key ns-name k)
       :db/valueType type
       :db/cardinality card})))

(defn load-schema []
  (let [f (schema-path)]
    (if (.exists f) (slurp-edn f) [])))

(defn merge-schema! [new-attrs]
  (let [existing (load-schema)
        by-ident (into {} (map (juxt :db/ident identity)) existing)
        merged-by-ident (reduce (fn [acc {:keys [db/ident] :as attr}]
                                   (if (contains? acc ident) acc (assoc acc ident attr)))
                                 by-ident
                                 new-attrs)
        merged (vec (sort-by (comp str :db/ident) (vals merged-by-ident)))]
    (spit (schema-path) (str ";; schema.edn — Datomic/Datascript 互換スキーマ定義（自動生成 by edn-datomize.bb）\n"
                              ";; :db/ident 属性定義のリスト。Datomic 固有キー(:db.install/_attribute 等)は使わない。\n"
                              ";; 手編集禁止 — 再生成すると上書きされる。\n"
                              ";;\n"
                              ";; kotoba/schema.edn (このリポジトリの手書き domain schema: :factory/* /\n"
                              ";; :production-order/* 等)とは別物 — こちらは tx-data 化した manifest.edn /\n"
                              ";; cells/*.edn / lex/*.edn の属性定義のみを集約する（kotoba/schema.edn とは\n"
                              ";; ident が重複しないよう既存キーの namespace を尊重して生成している）。\n\n"
                              (pr-str merged)
                              "\n"))
    merged))

(defn wrap-map! [rel-path ns-name]
  (let [f (io/file root rel-path)
        content (slurp-edn f)]
    (if (already-tx-data? content)
      (println "skip (already tx-data):" rel-path)
      (let [entity (entity-from-map content ns-name)
            attrs (schema-attrs content ns-name)]
        (spit f (pr-str [entity]))
        (merge-schema! attrs)
        (println "wrapped" rel-path "->" (count entity) "attrs, ns=" ns-name)))))

(defn -main [& args]
  (let [[mode a b] args]
    (case mode
      "wrap-map" (wrap-map! a b)
      (do (println "usage: bb edn-datomize.bb wrap-map <path> <ns>")
          (System/exit 1)))))

(apply -main *command-line-args*)
