# TourneyBot

This project is for the web application used to run the Houston Indoor Ultimate
tournament. An installation of this project can be seen at [houstonindoor.com]

The Houston Indoor tournament uses a [Swiss system] with some unique criteria.
In particular, since there is typically only one field available for an indoor
tournament, field time needs to be maximized with a game always playing. The
tournament is scheduled with exactly one minute of down time between games; just
enough time for teams to switch on and off the field.

This is a challenge for a Swiss system where you do not know the next round's
matchups until all the games in a round are played. This application provides
tools for dealing with this situation and ensuring that a "forward schedule" is
always available.

In addition, it provides a mobile-friendly website where players can see the
current schedule, their team results, and general tournament information. The
public-facing website receives near-realtime updates of all tournament
information, so players do not need to refresh the page in order to see the
latest information. See the [architecture] section for more information about
this feature.

## Screenshots

Here are some [screenshots](screenshots/) of TourneyBot.

## Development Setup

Install [Leiningen] and [Node.js]

```sh
# install node_modules (one-time)
npm install

# you may wish the run the following commands in separate console tabs / windows

# build CLJS files
lein clean && lein cljsbuild auto

# compile LESS into CSS
grunt watch

# run a local web server out of public/ on port 9955
node server.js 9955

# produce a build in the 00-publish/ folder
grunt build
```

## Installation

The steps for starting a new tournament are roughly as follows:

1. Make sure the CLJS-generated files exist by running `lein clean && lein cljsbuild once`
1. Create a build in the `00-publish` folder with the command `grunt build`
1. Copy the `00-publish/` folder to a public web server that has PHP installed.
1. Edit the `info.md` file as appropriate for your tournament.
1. Edit the `tournament.json` file as appropriate for your tournament.
1. Set a password in `api.php`

## Architecture

TourneyBot can be thought of as two separate applications that are designed to
work together: the client side and the admin side.

The entire tournament state is held in the `tournament.json` file. The client
side polls for that file every few seconds and updates the UI accordingly. The
admin side contains functions that modify `tournament.json` and save it to the
server. You could, for example, run an entire tournament by editing the
`tournament.json` file directly.

The Info page is held in `info.md` and requested on initial load, then again
every 5 minutes. It is expected that the info page will not change very much
while the tournament is happening.

The role of PHP in TourneyBot is minimal and could be swapped out for something
else easily. It was just the most convenient hosting option for me for the 2016
tournament.

Currently, the admin side architecture is such that only **one instance** of it
is allowed at a time. If you have more than one instance running at a time,
there is a dangerous race condition that will occur where each instance will
silently overwrite edits made in the other. This is obviously bad and fixing it
is tracked at [Issue #10].

## Future Development

TourneyBot was primarily created for the 2016 Houston Indoor Ultimate Tournament
and has a lot of code to deal with the unique constraints of that tournament.

It would be great to extend this project and make it more flexible for other
Ultimate tournament formats. Please reach out or post in the [issues] if you are
interested in helping with that.

## License

[ISC License]

[houstonindoor.com]:http://houstonindoor.com/2016
[architecture]:#architecture
[Swiss system]:https://en.wikipedia.org/wiki/Swiss-system_tournament
[Leiningen]:http://leiningen.org
[Node.js]:http://nodejs.org
[Issue #10]:https://github.com/oakmac/tourney-bot/issues/10
[issues]:https://github.com/oakmac/tourney-bot/issues
[ISC License]:LICENSE.md
