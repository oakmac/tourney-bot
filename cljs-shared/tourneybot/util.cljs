(ns tourneybot.util
  "Utility functions shared on both the Index and Admin sides.")

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def tourney-bot-url "https://github.com/oakmac/tourney-bot")

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
;; Predicates
;;------------------------------------------------------------------------------

(defn one? [x]
  (= x 1))

;;------------------------------------------------------------------------------
;; DOM Wrappers
;;------------------------------------------------------------------------------

(defn by-id [id]
  (js/document.getElementById id))

;;------------------------------------------------------------------------------
;; Game Functions
;;------------------------------------------------------------------------------

(defn game->date
  "Returns just the date string from a game."
  [game]
  (-> game
      :start-time
      (subs 0 10)))

(defn game->time
  "Returns just the time string from a game."
  [game]
  (-> game
      :start-time
      (subs 11 15)))

;;------------------------------------------------------------------------------
;; AJAX
;;------------------------------------------------------------------------------

;; TODO: make a generic AJAX function and combine fetch-json-as-cljs and
;;       fetch-ajax-text

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
            clj-result (js->clj js-result :keywordize-keys true)]
        (if (and js-result clj-result)
          (success-fn clj-result)
          (error-fn))))))

(def always-nil (constantly nil))

(defn fetch-json-as-cljs
  "Makes an AJAX request to an HTTP GET endpoint expecting JSON.
   Parses JSON into CLJS and keywordizes map keys."
  ([url success-fn]
   (fetch-json-as-cljs url success-fn always-nil))
  ([url success-fn error-fn]
   (doto (js/XMLHttpRequest.)
     (.addEventListener "load" (partial fetch-json-as-cljs2 success-fn error-fn))
     (.addEventListener "error" error-fn)
     (.addEventListener "abort" error-fn)
     (.open "get" (str url "?_c=" (random-uuid)))
     (.send))))

(defn- fetch-ajax-text2
  [success-fn error-fn js-evt]
  (let [status (aget js-evt "target" "status")]
    (if-not (http-success? status)
      (error-fn)
      (success-fn (aget js-evt "target" "responseText")))))

(defn fetch-ajax-text
 "Makes an AJAX request to an HTTP GET endpoint expecting raw text."
 ([url success-fn]
  (fetch-ajax-text url success-fn always-nil))
 ([url success-fn error-fn]
  (doto (js/XMLHttpRequest.)
    (.addEventListener "load" (partial fetch-ajax-text2 success-fn error-fn))
    (.addEventListener "error" error-fn)
    (.addEventListener "abort" error-fn)
    (.open "get" (str url "?_c=" (random-uuid)))
    (.send))))
