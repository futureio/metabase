(ns metabase.models.field-values
  (:require [clojure.tools.logging :as log]
            [metabase.util :as u]
            [toucan
             [db :as db]
             [models :as models]]))

(def ^:const ^Integer low-cardinality-threshold
  "Fields with less than this many distinct values should automatically be given a special type of `:type/Category`."
  300)

(def ^:private ^:const ^Integer entry-max-length
  "The maximum character length for a stored `FieldValues` entry."
  100)

(def ^:private ^:const ^Integer total-max-length
  "Maximum total length for a `FieldValues` entry (combined length of all values for the field)."
  (* low-cardinality-threshold entry-max-length))


;; ## Entity + DB Multimethods

(models/defmodel FieldValues :metabase_fieldvalues)

(u/strict-extend (class FieldValues)
  models/IModel
  (merge models/IModelDefaults
         {:properties  (constantly {:timestamped? true})
          :types       (constantly {:human_readable_values :json, :values :json})
          :post-select (u/rpartial update :human_readable_values #(or % {}))}))


;; ## `FieldValues` Helper Functions

(defn field-should-have-field-values?
  "Should this `Field` be backed by a corresponding `FieldValues` object?"
  {:arglists '([field])}
  [{:keys [base_type special_type visibility_type] :as field}]
  {:pre [visibility_type
         (contains? field :base_type)
         (contains? field :special_type)]}
  (and (not (contains? #{:retired :sensitive :hidden :details-only} (keyword visibility_type)))
       (not (isa? (keyword base_type) :type/DateTime))
       (or (isa? (keyword base_type) :type/Boolean)
           (isa? (keyword special_type) :type/Category)
           (isa? (keyword special_type) :type/Enum))))


(defn- values-less-than-total-max-length?
  "`true` if the combined length of all the values in DISTINCT-VALUES is below the
   threshold for what we'll allow in a FieldValues entry. Does some logging as well."
  [distinct-values]
  (let [total-length (reduce + (map (comp count str)
                                    distinct-values))]
    (u/prog1 (<= total-length total-max-length)
      (log/debug (format "Field values total length is %d (max %d)." total-length total-max-length)
                 (if <>
                   "FieldValues are allowed for this Field."
                   "FieldValues are NOT allowed for this Field.")))))

(defn- cardinality-less-than-threshold?
  "`true` if the number of DISTINCT-VALUES is less that `low-cardinality-threshold`.
   Does some logging as well."
  [distinct-values]
  (let [num-values (count distinct-values)]
    (u/prog1 (<= num-values low-cardinality-threshold)
      (log/debug (if <>
                   (format "Field has %d distinct values (max %d). FieldValues are allowed for this Field." num-values low-cardinality-threshold)
                   (format "Field has over %d values. FieldValues are NOT allowed for this Field." low-cardinality-threshold))))))


(defn- distinct-values
  "Fetch a sequence of distinct values for FIELD that are below the `total-max-length` threshold.
   If the values are past the threshold, this returns `nil`."
  [field]
  (require 'metabase.db.metadata-queries)
  (let [values ((resolve 'metabase.db.metadata-queries/field-distinct-values) field)]
    (when (cardinality-less-than-threshold? values)
      (when (values-less-than-total-max-length? values)
        values))))


(defn create-or-update-field-values!
  "Create or update the FieldValues object for FIELD. If the FieldValues object already exists, then update values for
   it; otherwise create a new FieldValues object with the newly fetched values."
  [field & [human-readable-values]]
  (let [field-values (FieldValues :field_id (u/get-id field))
        values       (distinct-values field)
        field-name   (or (:name field) (:id field))]
    (cond
      ;; if the FieldValues object already exists then update values in it
      (and field-values values)
      (do
        (log/debug (format "Storing updated FieldValues for Field %s..." field-name))
        (db/update! FieldValues (u/get-id field-values)
          :values values))
      ;; if FieldValues object doesn't exist create one
      values
      (do
        (log/debug (format "Storing FieldValues for Field %s..." field-name))
        (db/insert! FieldValues
          :field_id              (u/get-id field)
          :values                values
          :human_readable_values human-readable-values))
      ;; otherwise this Field isn't eligible, so delete any FieldValues that might exist
      :else
      (db/delete! FieldValues :field_id (u/get-id field)))))


(defn field-values->pairs
  "Returns a list of pairs (or single element vectors if there are no human_readable_values) for the given
   `FIELD-VALUES` instance."
  [{:keys [values human_readable_values] :as field-values}]
  (if (seq human_readable_values)
    (map vector values human_readable_values)
    (map vector values)))

(defn create-field-values-if-needed!
  "Create `FieldValues` for a `Field` if they *should* exist but don't already exist.
   Returns the existing or newly created `FieldValues` for `Field`."
  {:arglists '([field] [field human-readable-values])}
  [{field-id :id :as field} & [human-readable-values]]
  {:pre [(integer? field-id)]}
  (when (field-should-have-field-values? field)
    (or (FieldValues :field_id field-id)
        (create-or-update-field-values! field human-readable-values))))

(defn save-field-values!
  "Save the `FieldValues` for FIELD-ID, creating them if needed, otherwise updating them."
  [field-id values]
  {:pre [(integer? field-id) (coll? values)]}
  (if-let [field-values (FieldValues :field_id field-id)]
    (db/update! FieldValues (u/get-id field-values), :values values)
    (db/insert! FieldValues :field_id field-id, :values values)))

(defn clear-field-values!
  "Remove the `FieldValues` for FIELD-ID."
  [field-id]
  {:pre [(integer? field-id)]}
  (db/delete! FieldValues :field_id field-id))


(defn- table-ids->table-id->is-on-demand?
  "Given a collection of TABLE-IDS return a map of Table ID to whether or not its Database is subject to 'On Demand'
   FieldValues updating. This means the FieldValues for any Fields belonging to the Database should be updated only
   when they are used in new Dashboard or Card parameters."
  [table-ids]
  (let [table-ids            (set table-ids)
        table-id->db-id      (when (seq table-ids)
                               (db/select-id->field :db_id 'Table :id [:in table-ids]))
        db-id->is-on-demand? (when (seq table-id->db-id)
                               (db/select-id->field :is_on_demand 'Database
                                 :id [:in (set (vals table-id->db-id))]))]
    (into {} (for [table-id table-ids]
               [table-id (-> table-id table-id->db-id db-id->is-on-demand?)]))))

(defn update-field-values-for-on-demand-dbs!
  "Update the FieldValues for any Fields with FIELD-IDS if the Field should have FieldValues and it belongs to a
   Database that is set to do 'On-Demand' syncing."
  [field-ids]
  (let [fields (when (seq field-ids)
                 (filter field-should-have-field-values?
                         (db/select ['Field :name :id :base_type :special_type :visibility_type :table_id]
                           :id [:in field-ids])))
        table-id->is-on-demand? (table-ids->table-id->is-on-demand? (map :table_id fields))]
    (doseq [{table-id :table_id, :as field} fields]
      (when (table-id->is-on-demand? table-id)
        (log/debug
         (format "Field %d '%s' should have FieldValues and belongs to a Database with On-Demand FieldValues updating."
                 (u/get-id field) (:name field)))
        (create-or-update-field-values! field)))))
