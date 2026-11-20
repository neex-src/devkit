# devkit

All the code here is written by AI (Claude Code) and has not been reviewed.

A dev machine in one Podman container: Linux (Arch) with IntelliJ IDEA inside, shown in your
browser. IntelliJ, its plugins and everything you start from it run in the container, not on the
host with your rights. The image comes with the Deliberator and Inline Reading Mode plugins
installed.

## What you need

- [Podman](https://podman.io)
- [bb](https://babashka.org) (babashka)
- [fzf](https://github.com/junegunn/fzf), for the menu
- the two plugin zips, from the Releases pages of `deliberator` and `inliner`, in `.scratch/plugins/`

No X server on the host.

## Start

    bb vm

That is the menu. Until the VM runs it shows one next step (Podman, the image, the VM). Press
Enter on it. Building the image takes about 10 minutes and 13 GB.

## The menu

Two tabs, Tab switches.

`vm`, the things you look at:

- the VM itself; Enter starts or stops it
- IntelliJ; Enter opens it in the browser
- what is mounted in the VM
- what runs next to the VM: Floci (a local AWS) and its resources, databases, services. Enter on
  one: logs, restart, stop, a shell in it
- the repos in the VM, with branch and changes. Enter on one: open in IntelliJ, a shell there,
  pull, push, switch branch, remove
- Deliberator's workspaces, when there are any
- the repos you can import; Enter, yes, and it is in the VM

`setup`, the things you do once or rarely: the Podman machine, the image, plugins, root
certificates, the desktop, the folders, a shell, logs. When one of them needs doing, a yellow line
shows on top of `vm`.

Esc leaves the menu; the VM keeps running.

## Commands

The same without the menu:

    bb vm:build            build the image
    bb vm:up               start it, prints the URL (http://127.0.0.1:14500/)
                           --work DIR     your work folder (default .scratch/work)
                           --repos DIR    a folder of bare repos (default .scratch/repos)
                           --desktop xfce|i3
    bb vm:open             open IntelliJ in the browser
    bb vm:import NAME      a working copy of /repos/NAME.git in the work folder
    bb vm:down             stop it (home and work folder stay)
    bb vm:status           state, URL, memory, mounts, repos
    bb vm:shell            a shell inside, as the user dev
    bb vm:logs             the logs of the IDE and the display
    bb ca:install FILE...  extra root certificates (for a TLS-inspecting proxy) everywhere

## How it works

- The image is built from the official Arch Linux image. On arm64 there is none, so the Arch
  Linux ARM root tarball is downloaded, checked and imported.
- systemd runs inside. IntelliJ is a service and comes back by itself if it dies.
- xpra shows the X display as a web page on 127.0.0.1:14500. Closing the browser changes
  nothing, the session lives in the VM.
- `/home/dev` is a Podman volume. It survives `bb vm:down` and a new image.
- Your work folder is mounted at the same path as on the host, with `/work` as a link to it.
- Repos come from the host: the bare repos in `/repos` are the "remote". A push from the VM lands
  in the host's bare repo. There are no credentials and no `gh` in the VM.
- `podman` inside the VM talks to the host's Podman. A container started from the VM (Floci,
  Postgres, a service) runs next to the VM, not inside it, and its ports answer at
  `localhost:PORT` inside the VM too.

## What it does on your machine

- Where Podman runs its containers in a VM (a Podman machine), makes one (6 CPUs, 12 GB, 80 GB
  disk) unless there is one.
- Runs one container, `devkit`, with your work folder and the repos folder mounted.
- The container has network, so git, Maven and the IDE work as usual. It sees every port the
  containers next to it publish.
- The container gets the Podman socket, so the tools in it can start containers next to it. That
  is what Deliberator needs. It also means the container is not a closed box.

A locked down mode (no network, no Podman socket, repos read only) does not exist yet; it can be
added. In that mode IntelliJ, the plugins and reus would work; Deliberator's containers would not.
