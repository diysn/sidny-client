# Sidny Client

This is a proof-of-concept client for the SIDNY social network.

## Contributing

Please contribute.

Feel free to fork the project and make your own version. Pull requests are welcome, but should be 
focused on core functionality. If you want more features, please create your own client 
(forking this one if you so wish).

## Development

The project is written in ClojureScript using [shadow-cljs](https://github.com/thheller/shadow-cljs) and
[reagent](https://github.com/reagent-project/reagent).

To run it locally (and work on it) you will need to install [Clojure](https://clojure.org/guides/install_clojure) and 
[node](https://github.com/nodesource/distributions#installation-instructions).

To install additional libraries, change into the project directory and run:
`npm install`

To start a hot-loading development server run:
`npm run watch`

And then visit [localhost:8280](http://localhost:8280) to view the client.
