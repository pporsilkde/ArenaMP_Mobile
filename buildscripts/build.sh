#!/bin/bash

set -e
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $DIR

export ARCH="arm"
export CCACHE="false"
ASAN="false"
DEPLOY_RESOURCES="true"
LTO="${LTO:-false}"
LTO_JOBS="${LTO_JOBS:-1}"
GENERATE_DEBUG_SYMBOLS="${GENERATE_DEBUG_SYMBOLS:-true}"
BUILD_TYPE="release"
BUILD_STAGE="all"
CFLAGS="-fPIC"
CXXFLAGS="-fPIC -frtti -fexceptions"
LDFLAGS=""

usage() {
	echo "Usage: ./build.sh [--help] [--asan] [--arch arch] [--debug|--release]"
	echo "	--help: print this message"
	echo "	--arch: build for specified architecture [arm, arm64, x86_64, x86] (default: arm)"
	echo "	--asan: build with AddressSanitizer enabled"
	echo "	--no-resources: don't deploy the resources (used in full-build.sh)"
	echo "	--lto: use LTO for linking"
	echo "	--ccache: use ccache to speed up repeated builds"
	echo "	--dependencies-only: build Android native dependencies and stop"
	echo "	--client-only: build the ArenaMP Android client and deploy it"
	echo "	--client-server: build/deploy ArenaMP Android client + dedicated server"
	echo "	--ng-gl4es-only: rebuild/deploy only Sisah2 NG-GL4ES (keeps OpenMW and other deps cached)"
	echo "	--debug: produce a debug build without optimizations"
	echo "	--release: produce a release build with optimizations (default)"
	exit 0
}

# Parse command-line arguments
while [[ $# -gt 0 ]]; do
	key="$1"

	case $key in
		--help)
			usage
			shift
			;;
		--arch)
			export ARCH="$2"
			shift 2
			;;
		--asan)
			ASAN=true
			shift
			;;
		--lto)
			LTO=true
			shift
			;;
		--ccache)
			export CCACHE="true"
			shift
			;;
		--dependencies-only)
			BUILD_STAGE="dependencies"
			shift
			;;
		--client-only)
			BUILD_STAGE="client"
			shift
			;;
		--client-server)
			BUILD_STAGE="client-server"
			shift
			;;
		--ng-gl4es-only)
			BUILD_STAGE="ng-gl4es"
			shift
			;;
		--debug)
			BUILD_TYPE="debug"
			shift
			;;
		--release)
			BUILD_TYPE="release"
			shift
			;;
		--no-resources)
			DEPLOY_RESOURCES="false"
			shift
			;;
		*)
			echo "Invalid argument: $key"
			exit 1
			;;
	esac
done

if [[ $ASAN = true && $ARCH != "arm" && $ARCH != "arm64" ]]; then
	echo "AddressSanitizer is only supported on arm and aarch64 architectures"
	exit 1
fi

source ./include/version.sh

if [ $ASAN = true ]; then
	CFLAGS="$CFLAGS -fsanitize=address -fuse-ld=gold -fno-omit-frame-pointer"
	CXXFLAGS="$CXXFLAGS -fsanitize=address -fuse-ld=gold -fno-omit-frame-pointer"
	LDFLAGS="$LDFLAGS -fsanitize=address -fuse-ld=gold -fno-omit-frame-pointer"
fi

if [ $BUILD_TYPE = "release" ]; then
	CFLAGS="$CFLAGS -O3"
	CXXFLAGS="$CXXFLAGS -O3"
else
	CFLAGS="$CFLAGS -O0 -g"
	CXXFLAGS="$CXXFLAGS -O0 -g"
fi

if [[ $LTO = "true" ]]; then
	if [[ ! "$LTO_JOBS" =~ ^[1-9][0-9]*$ ]]; then
		echo "Invalid LTO_JOBS value: $LTO_JOBS"
		exit 1
	fi
	CFLAGS="$CFLAGS -flto=thin"
	CXXFLAGS="$CXXFLAGS -flto=thin"
	# The final 100% link is the peak-memory phase. Limit ThinLTO backend
	# parallelism independently from normal compilation to avoid CI OOM kills.
	LDFLAGS="$LDFLAGS -flto=thin -flto-jobs=$LTO_JOBS -Wl,-plugin-opt=-emulated-tls -fuse-ld=gold"
fi

if [[ $ARCH = "arm" ]]; then
	CFLAGS="$CFLAGS -mthumb"
	CXXFLAGS="$CXXFLAGS -mthumb"
fi

echo ""
echo "================================================================================"
echo ""
echo "Build configuration:"
echo " - Architecture: $ARCH"
echo " - Build type: $BUILD_TYPE"
echo " - AddressSanitizer: $ASAN"
echo " - LTO enabled: $LTO"
echo " - ThinLTO jobs: $LTO_JOBS"
echo " - Generate debug symbol index: $GENERATE_DEBUG_SYMBOLS"
echo ""
echo " ------------------------------------------------------------------------------ "
echo ""
echo "Computed flags:"
echo " - CFLAGS: $CFLAGS"
echo " - CXXFLAGS: $CXXFLAGS"
echo " - LDFLAGS: $LDFLAGS"
echo ""
echo "================================================================================"
echo "(Please run ./clean.sh manually if you modify any of the options)"
echo ""

echo "==> Download and set up the NDK"
./include/download-ndk.sh
./include/setup-ndk.sh

HOST_NCPU=$(getconf _NPROCESSORS_ONLN 2>/dev/null || grep -c ^processor /proc/cpuinfo || echo 1)
NCPU="${BUILD_JOBS:-$HOST_NCPU}"
if [[ ! "$NCPU" =~ ^[1-9][0-9]*$ ]]; then
	echo "Invalid BUILD_JOBS value: $NCPU"
	exit 1
fi
if (( NCPU > HOST_NCPU )); then
	echo "==> BUILD_JOBS=$NCPU exceeds available CPUs ($HOST_NCPU); using $HOST_NCPU"
	NCPU=$HOST_NCPU
fi
echo "==> Build stage: $BUILD_STAGE"
echo "==> Build using $NCPU CPUs"
mkdir -p build/$ARCH/
mkdir -p prefix/$ARCH/

# NG-GL4ES has its own source identity so changing wrapper branch does not force
# a rebuild of Bullet/OSG/MyGUI/etc. Only the wrapper ExternalProject is reset.
GL4ES_REPO="${GL4ES_GIT_REPOSITORY:-https://github.com/Sisah2/NG-GL4ES.git}"
GL4ES_TAG="${GL4ES_GIT_TAG:-Openmw3}"
GL4ES_PATCHSET="arenamp-openmw3-android-r21e-v3"
GL4ES_CACHE_ID="$GL4ES_REPO@$GL4ES_TAG#$GL4ES_PATCHSET"
GL4ES_MARKER="build/$ARCH/.ng-gl4es-source"
if [[ ! -f "$GL4ES_MARKER" || "$(cat "$GL4ES_MARKER" 2>/dev/null || true)" != "$GL4ES_CACHE_ID" ]]; then
    echo "==> NG-GL4ES source changed: $GL4ES_CACHE_ID"
    rm -rf "build/$ARCH/NG-GL4ES-prefix" "build/$ARCH/NG-GL4ES-build"
    rm -f "prefix/$ARCH/lib/libng_gl4es.so" "prefix/$ARCH/lib/libspirv-cross-c-shared.so"
    printf '%s\n' "$GL4ES_CACHE_ID" > "$GL4ES_MARKER"
fi

# symlink lib64 -> lib so we don't get half the libs in one directory half in another
mkdir -p prefix/$ARCH/lib
# A fresh dependency cache has no prefix/include yet. NG-GL4ES installs
# its headers before several other dependencies, so create it explicitly.
mkdir -p prefix/$ARCH/include
ln -sf lib prefix/$ARCH/lib64
mkdir -p prefix/$ARCH/osg/lib
ln -sf lib prefix/$ARCH/osg/lib64

# generate command_wrapper.sh
cat include/command_wrapper_head.sh.in | \
	DIR=$DIR \
	ARCH=$ARCH \
	ENV_CFLAGS=$CFLAGS \
	ENV_CXXFLAGS=$CXXFLAGS \
	NDK_TRIPLET=$NDK_TRIPLET \
	ENV_LDFLAGS=$LDFLAGS \
		envsubst > build/$ARCH/command_wrapper.sh
cat include/command_wrapper_tail.sh.in >> build/$ARCH/command_wrapper.sh
chmod +x build/$ARCH/command_wrapper.sh

pushd build/$ARCH/

# Get CC/CXX/etc vars
source ./command_wrapper.sh true

# Build!
cmake ../.. \
	-DCMAKE_INSTALL_PREFIX=$DIR/prefix/$ARCH/ \
	-DARCH=$ARCH \
	-DBUILD_TYPE=$BUILD_TYPE \
	-DNDK_TRIPLET=$NDK_TRIPLET \
	-DANDROID_API=$ANDROID_API \
	-DABI=$ABI \
	-DBOOST_ARCH=$BOOST_ARCH \
	-DBOOST_ADDRESS_MODEL=$BOOST_ADDRESS_MODEL \
	-DFFMPEG_CPU=$FFMPEG_CPU \
	-DBUILD_JOBS="$NCPU" \
	-DARENAMP_REPOSITORY="${ARENAMP_REPOSITORY:-https://github.com/pporsilkde/AMP.git}" \
	-DARENAMP_GIT_TAG="${ARENAMP_GIT_TAG:-main}" \
	-DGL4ES_GIT_REPOSITORY="$GL4ES_REPO" \
	-DGL4ES_GIT_TAG="$GL4ES_TAG"

# Native dependency caches are restored by CI. Master/browser stay disabled; client and server are built together.

run_compact_client_build() {
	local full_log="$DIR/build/$ARCH/arenamp-build.full.log"
	echo "==> Compact ArenaMP client/server build output enabled"
	echo "==> Complete output will be stored in: $full_log"

	set +e
	cmake --build . --target arenamp --parallel "$NCPU" 2>&1 | \
		python3 "$DIR/tool/compact_cmake_output.py" --log "$full_log"
	local build_status=${PIPESTATUS[0]}
	set -e

	if (( build_status != 0 )); then
		echo "==> ArenaMP native client/server build failed with exit code $build_status"
		echo "==> Full compiler output: $full_log"
		if [[ -f "$full_log" ]]; then
			echo "==> Last 160 lines:"
			tail -n 160 "$full_log"
		fi
		return "$build_status"
	fi
}

case "$BUILD_STAGE" in
	dependencies)
		cmake --build . --target android-dependencies --parallel "$NCPU"
		;;
	client)
		run_compact_client_build
		;;
	client-server)
		run_compact_client_build
		;;
	ng-gl4es)
		cmake --build . --target NG-GL4ES --parallel "$NCPU"
		;;
	all)
		cmake --build . --parallel "$NCPU"
		;;
esac

popd

if [[ "$BUILD_STAGE" == "ng-gl4es" ]]; then
    echo "==> Deploying only NG-GL4ES"
    mkdir -p ../app/src/main/jniLibs/$ABI/
    rm -f ../app/src/main/jniLibs/$ABI/libspirv-cross-c-shared.so
    cp "prefix/$ARCH/lib/libng_gl4es.so" ../app/src/main/jniLibs/$ABI/
    if command -v "$NDK_TRIPLET-strip" >/dev/null 2>&1; then
        "$NDK_TRIPLET-strip" ../app/src/main/jniLibs/$ABI/libng_gl4es.so || true
    fi
    ls -lh ../app/src/main/jniLibs/$ABI/libng_gl4es.so
    echo "==> NG-GL4ES-only rebuild completed; ArenaMP client and other native dependencies were not rebuilt"
    exit 0
fi

if [[ "$BUILD_STAGE" == "dependencies" ]]; then
	echo "==> Native dependency checkpoint completed"
	exit 0
fi

echo "==> Native CMake target completed successfully"
echo "==> Installing shared libraries"

rm -rf ../app/wrap/
rm -rf ../app/src/main/jniLibs/$ABI/
mkdir -p ../app/src/main/jniLibs/$ABI/

# ArenaMP Android client produces libtes3mp.so directly
TES3MP_SO="build/$ARCH/arenamp-prefix/src/arenamp-build/libtes3mp.so"
if [[ ! -f "$TES3MP_SO" ]]; then
	echo "ArenaMP client output not found: $TES3MP_SO" >&2
	exit 1
fi
cp "$TES3MP_SO" ../app/src/main/jniLibs/$ABI/libtes3mp.so

# Dedicated ArenaMP server is compiled as an Android shared library and owned
# by ArenaServerService in a separate process.
SERVER_SO="build/$ARCH/arenamp-prefix/src/arenamp-build/libarenamp_server.so"
if [[ "$BUILD_STAGE" == "client-server" || "$BUILD_STAGE" == "all" ]]; then
	if [[ ! -f "$SERVER_SO" ]]; then
		echo "ArenaMP Android server output not found: $SERVER_SO" >&2
		exit 1
	fi
	cp "$SERVER_SO" ../app/src/main/jniLibs/$ABI/libarenamp_server.so
fi

# copy over libs we compiled, but report the exact missing file instead of a generic cp failure.
# Sisah2/Openmw3 links SPIRV-Cross into libng_gl4es.so; only that shared wrapper is packaged.
for lib in libopenal libSDL2 libhidapi libng_gl4es; do
	LIB_PATH="prefix/$ARCH/lib/${lib}.so"
	if [[ ! -f "$LIB_PATH" ]]; then
		echo "Required Android shared library is missing: $LIB_PATH" >&2
		find "prefix/$ARCH/lib" -maxdepth 1 -type f -name '*.so' -print 2>/dev/null || true
		exit 1
	fi
	cp "$LIB_PATH" ../app/src/main/jniLibs/$ABI/
done

# copy over libc++_shared
LIBCXX_SHARED=$(find ./toolchain/$ARCH/sysroot/usr/lib/$NDK_TRIPLET -iname "libc++_shared.so" -print -quit)
if [[ -z "$LIBCXX_SHARED" || ! -f "$LIBCXX_SHARED" ]]; then
	echo "libc++_shared.so was not found in the NDK sysroot" >&2
	exit 1
fi
cp "$LIBCXX_SHARED" ../app/src/main/jniLibs/$ABI/

if [[ $DEPLOY_RESOURCES = "true" ]]; then
	echo "==> Deploying resources"

	DST=$DIR/../app/src/main/assets/libopenmw/
	SRC=build/$ARCH/arenamp-prefix/src/arenamp-build/

	rm -rf "$DST" && mkdir -p "$DST"

	# resources
	cp -r "$SRC/resources" "$DST"

	# ArenaMP HUD/chat uses the Russo MyGUI font. Android packages only the
	# generated build/resources tree, so fail loudly if CMake forgot the TTF.
	if [[ ! -f "$DST/resources/mygui/RussoOne-Regular.ttf" ]]; then
		echo "Required ArenaMP HUD font is missing from generated resources: resources/mygui/RussoOne-Regular.ttf" >&2
		exit 1
	fi

	# Source revision and network identity are intentionally decoupled. Build the
	# newest AMP/main code, but advertise the parent compatibility commit expected
	# by existing ArenaMP PC servers. TES3MP reads line 2 of resources/version.
	ARENAMP_SOURCE=build/$ARCH/arenamp-prefix/src/arenamp
	ACTUAL_AMP_SHA=$(git -C "$ARENAMP_SOURCE" rev-parse HEAD | tr -d '\r\n')
	COMPAT_AMP_SHA="${ARENAMP_NETWORK_COMMIT:-0f659371bcbaf9e7e6b94bd6bcb7a81970082234}"
	if [[ ! "$COMPAT_AMP_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
		echo "Invalid ARENAMP_NETWORK_COMMIT: '$COMPAT_AMP_SHA' (expected 40 hex characters)" >&2
		exit 1
	fi
	COMPAT_AMP_SHA="${COMPAT_AMP_SHA,,}"
	VERSION_NAME=$(sed -n '1p' "$DST/resources/version" | tr -d '\r\n')
	VERSION_TAG_HASH=$(sed -n '3p' "$DST/resources/version" | tr -d '\r\n')
	printf '%s\n%s\n%s\n' "$VERSION_NAME" "$COMPAT_AMP_SHA" "$VERSION_TAG_HASH" > "$DST/resources/version"
	PACKAGED_AMP_SHA=$(sed -n '2p' "$DST/resources/version" | tr -d '\r\n')
	if [[ "$PACKAGED_AMP_SHA" != "$COMPAT_AMP_SHA" ]]; then
		echo "ArenaMP compatibility identity packaging failed: packaged=$PACKAGED_AMP_SHA expected=$COMPAT_AMP_SHA" >&2
		exit 1
	fi
	printf '%s\n' "$ACTUAL_AMP_SHA" > "$DST/arenamp-source-revision.txt"
	echo "==> ArenaMP source revision:       ${ACTUAL_AMP_SHA:0:10}"
	echo "==> ArenaMP network compatibility: ${PACKAGED_AMP_SHA:0:10}"

	# global config
	mkdir -p "$DST/openmw/"
	cp "$SRC/defaults.bin" "$DST/openmw/"
	cp "$SRC/gamecontrollerdb.txt" "$DST/openmw/"
	if [[ -f "$SRC/tes3mp-client-default.cfg" ]]; then
		cp "$SRC/tes3mp-client-default.cfg" "$DST/openmw/"
	else
		echo "Required ArenaMP client config is missing: $SRC/tes3mp-client-default.cfg" >&2
		exit 1
	fi
	# cp "$DIR/../app/settings-default.cfg" "$DST/openmw/"
	cat "$SRC/openmw.cfg" | grep -v "data=" | grep -v "data-local=" >> "$DST/openmw/openmw.base.cfg"
	cat "$DIR/../app/openmw.base.cfg" >> "$DST/openmw/openmw.base.cfg"

	# licensing info
	cp "$DIR/../3rdparty-licenses.txt" "$DST"

	if [[ "$BUILD_STAGE" == "client-server" || "$BUILD_STAGE" == "all" ]]; then
		echo "==> Deploying ArenaMP dedicated-server runtime assets"
		SERVER_DST="$DIR/../app/src/main/assets/arenamp-server"
		rm -rf "$SERVER_DST"
		mkdir -p "$SERVER_DST/server" "$SERVER_DST/resources"
		cp -a "$ARENAMP_SOURCE/server/." "$SERVER_DST/server/"
		# Windows-only Lua modules cannot load on Android. CoreScripts use the
		# standard Lua io/json fallback on non-Windows hosts, so omit DLLs.
		find "$SERVER_DST/server" -type f -name '*.dll' -delete
		# Android cannot load the PC cjson.dll from portable storage. Bundle an
		# API-compatible pure-Lua cjson module backed by the CoreScripts dkjson.
		mkdir -p "$SERVER_DST/server/lib/lua"
		cp "$DIR/../app/server-cjson-compat.lua" "$SERVER_DST/server/lib/lua/cjson.lua"
		cp "$SRC/tes3mp-server-default.cfg" "$SERVER_DST/tes3mp-server-default.cfg"
		cp "$DST/resources/version" "$SERVER_DST/resources/version"
		printf 'source=%s\ncompat=%s\nandroid-server-v1-5\n' "$ACTUAL_AMP_SHA" "$COMPAT_AMP_SHA" > "$SERVER_DST/runtime-stamp.txt"
	fi
fi

if [ $ASAN = true ]; then
	cp ./toolchain/$ARCH/lib64/clang/*/lib/linux/libclang_rt.asan-$ASAN_ARCH-android.so "../app/src/main/jniLibs/$ABI/"
	mkdir -p ../app/wrap/res/lib/$ABI/
	sed "s/@ASAN_ARCH@/$ASAN_ARCH/g" < include/asan-wrapper.sh > "../app/wrap/res/lib/$ABI/wrap.sh"
	chmod +x "../app/wrap/res/lib/$ABI/wrap.sh"
fi

if [[ "$GENERATE_DEBUG_SYMBOLS" == "true" ]]; then
	echo "==> Preparing debug symbols"

	# copy unstripped libs to aid debugging
	rm -rf "./symbols/$ABI/" && mkdir -p "./symbols/$ABI/"
	cp "./build/$ARCH/openal-prefix/src/openal-build/libopenal.so" "./symbols/$ABI/"
	cp "./build/$ARCH/sdl2-prefix/src/sdl2-build/obj/local/$ABI/libSDL2.so" "./symbols/$ABI/"
	cp "./build/$ARCH/sdl2-prefix/src/sdl2-build/obj/local/$ABI/libhidapi.so" "./symbols/$ABI/"
	cp "./build/$ARCH/arenamp-prefix/src/arenamp-build/libtes3mp.so" "./symbols/$ABI/libtes3mp.so"
	if [[ -f "./build/$ARCH/arenamp-prefix/src/arenamp-build/libarenamp_server.so" ]]; then
		cp "./build/$ARCH/arenamp-prefix/src/arenamp-build/libarenamp_server.so" "./symbols/$ABI/libarenamp_server.so"
	fi
	# Unstripped NG-GL4ES artifact from the mandatory out-of-source CMake build.
	cp "./build/$ARCH/NG-GL4ES-build/libng_gl4es.so" "./symbols/$ABI/"
	cp "../app/src/main/jniLibs/$ABI/libc++_shared.so" "./symbols/$ABI/"
	if [ $ASAN = true ]; then
		cp ./toolchain/$ARCH/lib64/clang/*/lib/linux/libclang_rt.asan-$ASAN_ARCH-android.so "./symbols/$ABI/"
	fi

	PATH="$DIR/toolchain/ndk/prebuilt/linux-x86_64/bin/:$DIR/toolchain/$ARCH/$NDK_TRIPLET/bin/:$PATH" ./include/gdb-add-index ./symbols/$ABI/*.so
else
	echo "==> Skipping debug-symbol indexing (not needed for APK packaging)"
fi

# Gradle should strip native libraries, but do it explicitly and show what was packaged.
echo "==> Stripping packaged arm64 libraries"
$NDK_TRIPLET-strip ../app/src/main/jniLibs/$ABI/*.so
ls -lh ../app/src/main/jniLibs/$ABI/

echo "==> Success"
