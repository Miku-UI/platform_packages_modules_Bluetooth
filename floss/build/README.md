# Container build for Floss

This repo contains the Container-image build rule, used to generate the
(docker/podman) container image necessary to build Floss. If building a new
docker/podman image, run `container-build-image.py` with the tag `floss:latest`.

## Container binary: setting up podman (default)

On most Debian based machines, you should be able to simply use `apt-get` and
install these requisite packages.
```
sudo apt-get install \
  podman \
  uidmap
```

Then, we need to set up podman for [rootless
mode](https://docs.podman.io/en/latest/markdown/podman.1.html#rootless-mode):
```
sudo usermod --add-subuids 10000-75535 USERNAME
sudo usermod --add-subgids 10000-75535 USERNAME
```

## Container binary: setting up docker (alternative)

Follow the installation instructions at:
https://docs.docker.com/engine/install/, such as
https://docs.docker.com/engine/install/debian/.

Also consider configuring Docker to run in rootless mode:
https://docs.docker.com/engine/security/rootless/

## Generating the floss-build image

Run the following to generate the required image:
```
container-build-image.py --tag floss:latest
```

If you use the `docker` binary, add the flag: `--use-docker` when running
`container-build-image.py`.

This uses the default tag of `floss:latest` so you don't have to provide it
specifically when invoking `build-in-container.py`.

Run the following command to check if the image has been installed
```
$ podman images
REPOSITORY                TAG         IMAGE ID      CREATED         SIZE
localhost/floss           latest      6e66bc573bd7  8 minutes ago   3.27 GB
localhost/floss           buildtemp   8cd97b8cb7bb  10 minutes ago  3.25 GB
```

## Using the container image to build

Once the container image is built (and assuming it's tagged as `floss:latest`), you
should use the `build-in-container.py` script to build the current repo.

Basic build:
```
build-in-container.py
```

This script will use the local `floss:latest` (or pull it from the registry),
mount (or create) the `floss-out` volume to `/root/.floss` and the current
source to `/root/src` before running these commands in the container:

* `cd /root/src`
* `./build.py --run-bootstrap`
* `./build.py --libdir=/usr/lib/x86-64_linux_gnu/`

If you want to run the build more quickly (or pass other commands), run
`build-in-container.py --only-start`. This will only start the container for you
(doing the correct mounts) and will print the commands it would have run via
`<container_binary> exec` normally.
For example, you might want to run all unit tests after making a change:
```
./build-in-container.py --only-start
podman exec -it floss-container-runner /root/src/build.py --target test
```

Important: after syncing the repository, perform a clean build using
`build-in-container.py` to prevent unexpected build failures
