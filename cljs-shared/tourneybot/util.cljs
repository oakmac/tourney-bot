(ns tourneybot.util
  "Utility functions shared on both the Index and Admin sides.")

;;------------------------------------------------------------------------------
;; Logging
;;------------------------------------------------------------------------------

(defn js-log
  "Log a JavaScript thing."
  [js-thing]
  (js/console.log js-thing))

(defn log
  "Log a Clojure thing."
  [clj-thing]
  (js-log (pr-str clj-thing)))

(defn atom-logger
  [_kwd _the-atom old-state new-state]
  (log old-state)
  (log new-state)
  (js-log "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"))

;;------------------------------------------------------------------------------
;; DOM Wrappers
;;------------------------------------------------------------------------------

(defn by-id [id]
  (js/document.getElementById id))

;;------------------------------------------------------------------------------
;; AJAX
;;------------------------------------------------------------------------------

(defn- http-success? [status]
  (and (>= status 200)
       (< status 400)))

(defn- fetch-json-as-cljs2
  [success-fn error-fn js-evt]
  (let [status (aget js-evt "target" "status")]
    (if-not (http-success? status)
      (error-fn)
      (let [response-text (aget js-evt "target" "responseText")
            js-result (try (js/JSON.parse response-text)
                        (catch js/Error _error nil))
            clj-result (js->clj js-result :keywordize true)]
        (if (and js-result clj-result)
          (success-fn clj-result)
          (error-fn))))))

(defn fetch-json-as-cljs
  "Makes an AJAX request to an HTTP GET endpoint expecting JSON.
   Parses JSON into CLJS and keywordizes map keys."
  ([url success-fn]
   (fetch-json-as-cljs url success-fn (constantly nil)))
  ([url success-fn error-fn]
   (doto (js/XMLHttpRequest.)
     (.addEventListener "load" (partial fetch-json-as-cljs2 success-fn error-fn))
     (.addEventListener "error" error-fn)
     (.addEventListener "abort" error-fn)
     (.open "get" (str url "?_c=" (random-uuid)))
     (.send))))
