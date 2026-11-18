export XDG_RUNTIME_DIR=/run/user/1000
export CONTAINER_HOST=unix:///run/user/1000/podman/podman.sock
export DOCKER_HOST=$CONTAINER_HOST
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export DELIBERATOR_HOME=~/.deliberator
# the work folder and the Deliberator home in it (devkit-boot wrote them)
if [ -r /run/devkit.env ]; then set -a; . /run/devkit.env; set +a; fi
# Deliberator's command line and daemon, with nothing to set: `dlb` from a deliberator checkout in the work
# folder; the daemon jar that checkout built, else the one inside the IDE plugin of this image.
if [ -x "${DEVKIT_WORK:-/work}/deliberator/cli/dlb" ]; then
  PATH="${DEVKIT_WORK:-/work}/deliberator/cli:$PATH"
fi
if [ -z "$DELIBERATOR_JAR" ] && [ ! -f "${DEVKIT_WORK:-/work}/deliberator/core/target/deliberatord.jar" ] \
   && [ -f /opt/devkit/plugins/deliberator/lib/deliberatord.jar ]; then
  export DELIBERATOR_JAR=/opt/devkit/plugins/deliberator/lib/deliberatord.jar
fi
