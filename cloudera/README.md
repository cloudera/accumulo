# Build process

The build process consists of 5 main steps:

 1. Building java codes.
 2. Building native libraries for various Linux distributions.
 3. Creating parcels for those distributions.
 4. Generating repository for S3.
 5. Uploading the repository to S3.

These scripts are located under `cloudera` folder. The process should be started by `build.sh`.

The scripts will create some extra directories during the build:

 - `build-parcel`: parcels for various distributions are packaged and stored here.
 - `output-repo`: the contents of this directory will be uploaded to S3.

## Prerequisites

Accumulo and CDH is checked out to the same folder.

# build.sh

This file controls the whole build process of Accumulo. It can be run locally or by Jenkins. Main steps:

 1. Fetch GBN.
 2. Determine version from pom.xml (`1.9.2-cdh6.0.x-SNAPSHOT` will be `1.9.2`).
 3. Get current branch name and last commit id.
 4. Generate `cdh_version.properties` file.
 5. Build Accumulo with `mvn package`.
 6. Build native libraries and parcels for different platforms (`build-parcel.sh`).
 7. Create and upload repository to S3 (`post_build.sh`).
 8. Generate some extra artifacts for Jenkins.

# build-parcel.sh

Builds native libraries and parcel for the current distribution. It should be run within a docker container, but it isn't necessary.

Invocation:

`VERSION=1.9.2 GBN=123456 [DISTRO=el6] build-parcel.sh`

Main steps:

1. Create the output folder for the parcel `build-parcel/ACCUMULO-...`.
2. Populate it with the contents of `cloudera/parcel-extra`.
3. Build native libraries.
4. Translate the maven generated layout to parcel layout.
5. Add native libraries.
6. Generate `meta/parcel.json`.
7. Generate `meta/permissions.json`.
8. Create tarball and leave it under `build-parcel`.

# post_build.sh

Generates the directory and file structure for S3 and uploads it. This script should be invoked from `build.sh`. It uses `output-repo` directory.

Main steps:

1. Create a virtualenv and install cauldron scripts.
2. Generate the contents of `parcels`.
3. Generate `parcel.manifest`.
4. Generate `build.json`.
5. Upload it to S3.
6. Tag the build build.

