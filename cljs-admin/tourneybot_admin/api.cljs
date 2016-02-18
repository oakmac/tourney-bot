(ns tourneybot-admin.api
  "Function wrapper around API calls.")

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def api-url "api.php")

;;------------------------------------------------------------------------------
;; API Methods
;;------------------------------------------------------------------------------

(defn check-password
  "Checks that a password is valid.
   Passes the correct password string to callback-fn if valid.
   Else passes `false`"
  [pwd callback-fn]
  ;; TODO: write me
  nil)

(defn save-tournament-state!
  "Saves the tournament state to tournament.json"
  [new-state]
  ;; TODO: write me
  nil)

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
