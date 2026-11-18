export XDG_RUNTIME_DIR=/run/user/1000
export CONTAINER_HOST=unix:///run/user/1000/podman/podman.sock
export DOCKER_HOST=$CONTAINER_HOST
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export DELIBERATOR_HOME=~/.deliberator
# the work folder and the Deliberator home in it (devkit-boot wrote them)
if [ -r /run/devkit.env ]; then set -a; . /run/devkit.env; set +a; fi
