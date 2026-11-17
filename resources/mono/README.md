DISPLAYMER
AI GENERAITED
however testss pas
although... you know...
---

# devkit

A set of dev tools in one repo, and a dev VM to run them in.
Each folder was its own repo. Its history is kept: `git log -- reus/` and `git blame` work.

## Folders

- `deliberator/` - IntelliJ plugin (`deliberator/ide`) and a daemon + CLI to deliberate a microservice and event-driven AWS estate: environments, scenarios, runs. Start with `deliberator/README.md`.
- `inliner/` - IntelliJ plugin: read a called method inline, under the call.
- `reus/` - records everything an AI coding agent (Claude Code, Copilot) does as git commits in one extra repo, so you can look at every step.
- `ashamer/` - function-level git history for Clojure repos.
- `sling/` - "sling": a fake Ping tenant on one port, same URLs as the real one, to try things without the real service.
- `bb.edn`, `src/`, `image/` - the dev VM (podman container with IntelliJ in the browser).

## Start

You need git, babashka (`bb`) and podman.

```
git clone <this repo>
cd devkit
bb vm
```

`bb vm` is a menu: build the image, start it, open the URL it prints.

## Where the IDE plugins come from

The two plugins (deliberator and inliner) are not built on your machine.
They are zips attached to the GitHub releases of this repo.
Download them into one folder and pass it to the build: `bb vm:build --plugins DIR`.
Or install a zip by hand in IntelliJ: Settings, Plugins, the gear, Install Plugin from Disk.
