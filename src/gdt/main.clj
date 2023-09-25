(ns gdt.main
  (:require
   [clojure.zip :as zip]
   [org.httpkit.server :as hserver]
   [camel-snake-kebab.core :as csk]
   [org.httpkit.client :as hclient]
   [ring.middleware.params :as rmparams]
   [ring.middleware.resource :as rmresource]
   [huff.core :as h]
   [clojure.data.json :as json]
   [clojure.core.memoize :as memo]))

;; a little hacky, but we can upgrade+secure this later
(assert (some? (System/getenv "GIANTBOMB_API_KEY"))
        "GIANTBOMB_API_KEY={key} missing. start the clojure process with this key in the environment.")
(defonce api-key (System/getenv "GIANTBOMB_API_KEY"))

(defonce server (atom nil))

(defonce db (atom {}))

;; how often is a game released, or the metadata updated? giantbomb is entirely
;; read only, so we have some additional leeway here. half a day cache seems
;; reasonable for now.
;;
;; if you _must_ break the cache, provide a unique (superflous) http opt such
;; as: {:uri ..., :method :get, :cachebust 0}.
(def http
  (memo/ttl
   (fn memo-http [{:keys [url] :as opts}]
     (let [authd-opts (-> opts
                          (update-in [:query-params] assoc "format" "json")
                          (update-in [:query-params] assoc "api_key" api-key))]
       (try
         (let [resp (deref (hclient/request authd-opts))
               body (json/read-str (:body resp) :key-fn csk/->kebab-case-keyword)]
           (assert (and
                     (= 1 (:status-code body))
                     (= "OK" (:error body))) "Something went wrong with the Giant Bomb API.")
           ;; don't attempt to parse/negotiate any further than the xml layer.
           ;; the API returns a meta object with nested data - let consumers use
           ;; that metadata and the publicly available GB docs.
           (tap> {:resp resp})
           body)

         (catch Exception e
           ;; could be a whole bunch of things, but generically there's very
           ;; likely something wrong with the giantbomb API (invalid request,
           ;; unparseable response, unavailable, too many requests, etc.).
           ;;
           ;; @TODO: eject this key pair from the memoization on error
           (throw (ex-info "Something went wrong talking to the Giant Bomb API."
                           {:url url
                            :reason :giantbomb-api-unavailable}))))))

   :ttl/threshold (* 1000 60 60 12)))

(defn search-for-games [term]
  (let [body (http {:url "https://www.giantbomb.com/api/search"
                    :method :get
                    :query-params {"query" term
                                   #_#_"limit" "25"}})]
    (:results body)))

(defn ->api-filter [field stringables]
  (clojure.string/join "," (map #(str field ":" %) stringables)))

(comment
  (= "id:a,id:b,id:c" (->api-filter "id" ['a 'b 'c ]))
  )

(defn query-for-games [ids]
  (let [body (http {:url "https://www.giantbomb.com/api/games"
                    :method :get
                    :query-params {"filter" (->api-filter "id" ids)}})]
    (:results body)))

(defn add-game-to-cart [user-id game-id]
  (swap! db (fn [db]
              (assoc-in db [user-id game-id] true))))

(defn remove-game-from-cart [user-id game-id]
  (swap! db (fn [db]
              (if-not (get db user-id)
                db
                (update-in db [user-id] dissoc game-id)))))

(defn capture-game-details [game]
  (swap! db assoc-in [:games (str (:id game))] game))

(defn game-details [game-id]
  (get-in @db [:games game-id]))

(defn games-in-cart [user-id]
  (keys (get-in @db [user-id])))

(comment
  ;; sanity check
  (http {:url "http://www.giantbomb.com/api/game/3030-4725"
         :method :get})

  (add-game-to-cart 'a "game-1")
  (games-in-cart 'a)
  (remove-game-from-cart 'a "game-1")

  (clojure.pprint/pprint
   (search-for-games "warcraft"))

  (query-for-games ["4"])

  (clojure.pprint/pprint
   (query-for-games ["4" "27267" "70844"]))

  (do *e)
  )

(defn html
  "route html document helper"
  [children]
  (h/html
   [:<>
    [:hiccup/raw-html "<!DOCTYPE html>"]
    [:html
     [:head
      [:link {:type "text/css" :href "/main.css" :rel "stylesheet"}]
      [:script {:src "/main.js"}]
      #_(script-with-hashed-content "auth.js")]
     [:body {:style {:width "100%"
                     :height "100%"}}
      children]]]))

(defn list-of-games [term games]
  (into
   [:ol {:class "games-list"}
    (for [g games]
      [:li.game
       [:form {:method :post
               :action "/rent-interest"}
        [:img {:src (:thumb-url (:image g))
               :alt (str "awesome thumbnail for " (:name g))}]
        [:fieldset.controls
         [:button {:type "submit"} "Rent"]
         [:input {:type "hidden"
                  :name "term"
                  :value (or term "")}]

         [:input {:type "hidden"
                  :name "game-id"
                  :value (:id g)}]

         [:input {:type "hidden"
                  :name "game-name"
                  :value (:name g)}]]

        [:div.game-details
         [:span.game-name (:name g)]]]])]))

(defn search-view [term games]
  [:div {:class "search-view"}
   [:form {:class "search-form"
           :id "search-form"
           :action "/search"
           :method :get}
    [:label {:for "term"} "Find a game"]

    [:div.form-controls
     [:input {:type "search"
              :required true
              :id "term"
              :name "term"
              :value term}]
     [:button {:type "submit"} "Search"]

     [:span.spinner {:style {:visibility "hidden"}}]]]
   [list-of-games term games]])

(defn search [req]
  (let [term (get (:query-params req) "term")

        ;; as a nicety, it would be nice to show the highest rated games until
        ;; the user provides a specific term to search for, but the API makes
        ;; that seemingly cumbersome so we'll show an empty list instead
        games (if (some? term)
                (search-for-games term)
                [])

        ;; the API isn't great. its not easy to figure out how (or if you even
        ;; can) query for the details of multiple /games via id. instead, we're
        ;; just going to cache every game locally-- because every game will have
        ;; to have been a search result prior to being used anywhere else in
        ;; this app.
        _ (doall (map capture-game-details games))

        body [search-view term games]]
    {:status 200
     :headers {"content-type" "text/html"
               "location" (str (:uri req)
                               (when (:query-string req) (str "?" (:query-string req))))}
     :body (html body)}))

(defn cart-view [req]
  (let [game-ids (games-in-cart "user-1")
        games (map game-details game-ids)
        term (get (:params req) "term")

        body [:div.cart-view
              [:form.cart-form {:method :post
                                :action "/checkout"}
               [:a {:href (str "/search"
                               (when term (str "?term=" term)))}
                "< Continue shopping"]
               [:button {:type "submit"
                         :disabled (not (seq games))} "Checkout"]]
              [:ol.games-list.cart-items
               (for [g games]
                 [:li.game
                  [:form {:method :post
                          :action "/rent-disinterest"}
                   [:img {:src (:thumb-url (:image g))
                          :alt (str "awesome thumbnail for " (:name g))}]
                   [:fieldset.controls
                    ;; isn't html5 a bummer?
                    [:input {:type "hidden"
                             :name "_method"
                             :value "delete"}]
                    [:input {:type "hidden"
                             :name "game-id"
                             :value (:id g)}]

                    [:input {:type "hidden"
                             :name "term"
                             :value term}]
                    [:button {:type "submit"} "Remove from cart"]]

                   [:div.game-details
                    [:span.game-name (:name g)]]]])]]]
    {:status 200
     :headers {"content-type" "text/html"
               "cache-control" "no-cache, no-store, must-revalidate"}
     :body (html body)}))

(defn checkout-view [req]
  (let [body [:div.checkout
              [:h1 "Your games are on the way!"]
              [:a {:href "/search"} "< Continue shopping"]]]

    {:status 200
     :headers {"content-type" "text/html"}
     :body (html body)}))

(defn checkout [req]
  (let [game-ids (games-in-cart "user-1")
        _ (doall (map (partial remove-game-from-cart "user-1") game-ids))]
    {:status 303
     :headers {"content-type" "text/html"
               "location" "/checkout"}}))

(defn rent-interest [req]
  (let [game-id (get (:params req) "game-id")
        game-name (get (:params req) "game-name")
        term (get (:params req) "term")
        _ (add-game-to-cart "user-1" game-id)]
    {:status 303
     :headers {"content-type" "text/html"
               "location" (str "/cart?game-name=" game-name
                               "&game-id=" game-id
                               "&term=" term)}}))

(defn rent-disinterest [req]
  (let [game-id (get (:params req) "game-id")
        term (get (:params req) "term")
        _ (remove-game-from-cart "user-1" game-id)]
    {:status 303
     :headers {"content-type" "text/html"
               "location" (str "/cart" (when term (str "?term=" term)))}}))

(defn app [req]
  ;; let's try not to use a big router dep for a simple app, if possible.
  (cond
    (and (= "/search" (:uri req))
         (= :get (:request-method req))) (search req)

    (and (= "/rent-interest" (:uri req))
         (= :post (:request-method req))) (rent-interest req)

    (and (= "/rent-disinterest" (:uri req))
         (= :post (:request-method req))
         (= "delete" (get (:params req) "_method"))) (rent-disinterest req)

    (and (= "/cart" (:uri req))
         (= :get (:request-method req))) (cart-view req)

    (and (= "/checkout" (:uri req))
         (= :post (:request-method req))) (checkout req)

    (and (= "/checkout" (:uri req))
         (= :get (:request-method req))) (checkout-view req)

    :else
    ;; easy fallback for now
    {:status 404
     :body (html
             [:div
              [:p (str "The requested page \"" (:uri req) "\" could not be found.")]
              [:p
               "Did you want to "
               [:a {:href "/search"} "rent some games"]
               "?"]])

     ;; be a nice http citizen
     :headers {"location" "/search"}}))

(def server-spec (-> app
                     (rmparams/wrap-params)
                     (rmresource/wrap-resource "public")))

(defn -main [& args]
  (assert (some? (System/getenv "GIANTBOMB_API_KEY"))
          "GIANTBOMB_API_KEY={key} missing.")

  (println "starting... " args)

  ;; graceful shutdown
  (when @server ((deref server) :timeout 100))

  (println "http server available at 3000")

  (reset! server
          (hserver/run-server #'server-spec
                              {:port 3000})))

(comment

  (require '[portal.api :as p])
  (def p (p/open))
  (add-tap #'p/submit)
  (remove-tap #'p/submit)

  (-main)

  ;; simple sanity checks
  ;; https://github.com/ring-clojure/ring/blob/master/SPEC
  (app {:uri "/search"
        :request-method :get
        :query-params {"term" "warcraft"}})
  )
