(defproject tourney-bot "0.1.0"

  :description "Run the Houston Indoor Ultimate Tournament"
  :url "https://github.com/oakmac/tourney-bot"

  :license {:name "ISC License"
            :url "https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md"
            :distribution :repo}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]
                 [cljsjs/jquery "2.2.4-0"]
                 [cljsjs/marked "0.3.5-0"]
                 [cljsjs/moment "2.15.2-3"]
                 [rum "0.10.7"]]

  :plugins [[lein-cljsbuild "1.1.5"]]

  :source-paths ["src"]

  :clean-targets ["public/js/admin-dev.js"
                  "public/js/admin.min.js"
                  "public/js/client-dev.js"
                  "public/js/client.min.js"]

  :cljsbuild
    {:builds
      [{:id "admin-dev"
        :source-paths ["cljs-admin" "cljs-common"]
        :compiler {:output-to "public/js/admin-dev.js"
                   :optimizations :whitespace}}

       {:id "admin-prod"
        :source-paths ["cljs-admin" "cljs-common"]
        :compiler {:output-to "public/js/admin.min.js"
                   :optimizations :advanced
                   :pretty-print false}}

       {:id "client-dev"
        :source-paths ["cljs-client" "cljs-common"]
        :compiler {:output-to "public/js/client-dev.js"
                   :optimizations :whitespace}}

       {:id "client-prod"
        :source-paths ["cljs-client" "cljs-common"]
        :compiler {:output-to "public/js/client.min.js"
                   :optimizations :advanced
                   :pretty-print false}}]})
