# TourneyBot

An application to help run the Houston Ultimate Indoor Tournament.

TODO: upload screenshots

## Development Setup

Install [Leiningen] and [Node.js]

```sh
# install node_modules (one-time)
npm install

# build CLJS files
lein clean && lein cljsbuild auto

# compile LESS into CSS
grunt watch

# run a local web server out of public/ on port 9955
node server.js 9955
```

## Installation

TODO: write this

## License

[ISC License]

[Leiningen]:http://leiningen.org
[Node.js]:http://nodejs.org
[ISC License]:LICENSE.md
