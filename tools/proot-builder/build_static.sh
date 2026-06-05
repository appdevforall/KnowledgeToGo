#!/bin/bash
# -----------------------------------------------------------------------------
# Static Builder for Android (Multi-Architecture)
# Compiles self-contained static binaries for PRoot, Aria2c, Tar, Gzip, XZ, Rsync, Nano, and Less
# -----------------------------------------------------------------------------
set -e

# ==========================================
# 1. PARSE COMMAND LINE FLAGS
# ==========================================
REBUILD_PROOT=0
REBUILD_ARIA2=0
REBUILD_TAR=0
REBUILD_GZIP=0
REBUILD_RSYNC=0
REBUILD_XZ=0
REBUILD_NANO=0
REBUILD_LESS=0

# Default directories
BUILD_DIR="$(pwd)"
OUTPUT_DIR="$(pwd)/dist"

while [[ "$#" -gt 0 ]]; do
    case $1 in
        --rebuild-all)
            REBUILD_PROOT=1
            REBUILD_ARIA2=1
            REBUILD_TAR=1
            REBUILD_GZIP=1
            REBUILD_RSYNC=1
            REBUILD_XZ=1
            REBUILD_NANO=1
            REBUILD_LESS=1
            shift
            ;;
        --rebuild-proot) REBUILD_PROOT=1; shift ;;
        --rebuild-aria2) REBUILD_ARIA2=1; shift ;;
        --rebuild-tar)   REBUILD_TAR=1; shift ;;
        --rebuild-gzip)  REBUILD_GZIP=1; shift ;;
        --rebuild-rsync) REBUILD_RSYNC=1; shift ;;
        --rebuild-xz)    REBUILD_XZ=1; shift ;;
        --rebuild-nano)  REBUILD_NANO=1; shift ;;
        --rebuild-less)  REBUILD_LESS=1; shift ;;
        --build-dir)     BUILD_DIR="$2"; shift 2 ;;
        --out-dir)       OUTPUT_DIR="$2"; shift 2 ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [--rebuild-all] [--build-dir /opt/termux-build] [--out-dir /var/www/artifacts]"
            exit 1
            ;;
    esac
done

TERMUX_REPO="$BUILD_DIR/termux-packages"

echo "[1/4] Checking host dependencies..."
if ! command -v docker &> /dev/null; then 
    echo "ERROR: Docker is missing. Please install Docker to proceed."
    exit 1
fi

if ! command -v patchelf &> /dev/null; then
    sudo apt-get update
    sudo apt-get install -y patchelf binutils wget tar xz-utils
fi

echo "[2/4] Preparing termux-packages environment in $BUILD_DIR..."
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ ! -d "termux-packages" ]; then
    git clone --depth 1 https://github.com/termux/termux-packages.git
else
    cd termux-packages
    git restore .
    git pull
    cd ..
fi

# FIX FOR DOCKER PERMISSION DENIED:
# Docker runs as UID 1000 (builder). We must ensure these directories are writable by anyone.
echo ">> Adjusting permissions for Docker container..."
mkdir -p "$TERMUX_REPO/output" "$TERMUX_REPO/build" "$TERMUX_REPO/debs"
sudo chmod -R 777 "$TERMUX_REPO/output" "$TERMUX_REPO/build" "$TERMUX_REPO/debs"

echo "[3/4] Patching build scripts for static compilation..."
cd "$TERMUX_REPO"

# --- NCURSES PATCH ---
# Forces ncurses to inject the database of these terminals directly into the binary
echo 'TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --with-fallbacks=xterm-256color,xterm,linux,vt100"' >> packages/ncurses/build.sh

# --- PROOT PATCH ---
sed -i 's/"libtalloc"/"libtalloc-static, libtalloc"/' packages/proot/build.sh
cat << 'EOF' >> packages/proot/build.sh
termux_step_pre_configure() {
    echo ">> [IIAB] Scorched Earth for PRoot: Forcing static libtalloc..."
    # We removed the dynamic library to force the linker to use libtalloc.a
    rm -f $TERMUX_PREFIX/lib/libtalloc.so*

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
    CFLAGS+=" -static"
}
EOF

# --- ARIA2 PATCH ---
sed -i 's/TERMUX_PKG_DEPENDS="/TERMUX_PKG_DEPENDS="openssl-static, zlib-static, /' packages/aria2/build.sh
cat << 'EOF' >> packages/aria2/build.sh
termux_step_pre_configure() {
    echo ">> [IIAB] Compiling static libexpat from scratch..."
    local CURRENT_DIR=$(pwd)

    mkdir -p $TERMUX_PKG_TMPDIR/expat-build
    cd $TERMUX_PKG_TMPDIR/expat-build
    wget -q https://github.com/libexpat/libexpat/releases/download/R_2_8_0/expat-2.8.0.tar.gz
    tar xf expat-2.8.0.tar.gz
    cd expat-2.8.0
    ./configure --host=$TERMUX_HOST_PLATFORM --prefix=$TERMUX_PREFIX \
                --enable-static --disable-shared \
                --without-docbook --without-xmlwf --without-examples --without-tests

    make -j$(nproc) install

    cd $CURRENT_DIR

    echo ">> [IIAB] Injecting Static Default Provider and Lobotomizing Legacy crash..."
    # 1. We inject the static OpenSSL function into C++ so that it doesn't try to load default.so
    sed -i 's|defProv_ = OSSL_PROVIDER_load(NULL, "default");|extern "C" OSSL_provider_init_fn ossl_default_provider_init;\n    OSSL_PROVIDER_add_builtin(NULL, "default", ossl_default_provider_init);\n    defProv_ = OSSL_PROVIDER_load(NULL, "default");|g' $TERMUX_PKG_SRCDIR/src/Platform.cc

    # 2. We ignore the failures of the legacy provider (not needed for modern certificates)
    sed -i 's/throw DL_ABORT_EX("OSSL_PROVIDER_load.*//g' $TERMUX_PKG_SRCDIR/src/Platform.cc

    echo ">> [IIAB] Scorched Earth Tactic (Preserving Expat)..."
    rm -f $TERMUX_PREFIX/lib/libssl.so*
    rm -f $TERMUX_PREFIX/lib/libcrypto.so*
    rm -f $TERMUX_PREFIX/lib/libz.so*
    rm -f $TERMUX_PREFIX/lib/libexpat.so*

    # === TRIPLE INJECTION FOR STATIC C++ ===
    export CXX="$CXX -static-libstdc++"
    CXXFLAGS+=" -static-libstdc++"
    LDFLAGS+=" -static-libstdc++"

    export PKG_CONFIG="pkg-config --static"
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --enable-static --disable-shared"
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --without-libxml2 --with-libexpat"
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --without-libcares --without-sqlite3"
}
EOF

# --- TAR PATCH ---
sed -i -E 's/libandroid-glob/libandroid-glob-static, libandroid-glob/g' packages/tar/build.sh
sed -i -E 's/libiconv/libiconv-static, libiconv/g' packages/tar/build.sh
cat << 'EOF' >> packages/tar/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for GNU Tar..."
    rm -f $TERMUX_PREFIX/lib/libandroid-glob.so*
    rm -f $TERMUX_PREFIX/lib/libiconv.so*

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,--allow-multiple-definition"
    CFLAGS+=" -Wno-error=implicit-function-declaration -Dlchmod=chmod"
    export gl_cv_func_lchown_works=yes
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --disable-year2038"
}
EOF

# --- GZIP PATCH ---
cat << 'EOF' >> packages/gzip/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for GNU Gzip..."
    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
    export ac_cv_func_qsort_r=no
    export gl_cv_func_qsort_r=no
}
EOF

# --- XZ-UTILS PATCH ---
XZ_DIR=$(grep -lr "https://tukaani.org/xz/" packages/ | grep build.sh | head -n 1 | xargs dirname)
if [ -z "$XZ_DIR" ]; then echo ">> ERROR: Could not locate xz-utils in packages directory!"; exit 1; fi
XZ_PKG_NAME=$(basename "$XZ_DIR")

sed -i 's/--disable-static//g' "$XZ_DIR/build.sh" || true
cat << 'EOF' >> "$XZ_DIR/build.sh"
termux_step_pre_configure() {
    echo ">> Applying Static flags for XZ Utils..."
    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --enable-static --disable-shared"
}
termux_step_post_massage() {
    echo ">> Bypassing SOVERSION guard..."
    TERMUX_PKG_SOVERSION=""
}
EOF

# --- RSYNC PATCH ---
cat << 'EOF' >> packages/rsync/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for Rsync..."
    mkdir -p $TERMUX_PREFIX/lib/hidden_so
    mv $TERMUX_PREFIX/lib/libpopt.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true
    mv $TERMUX_PREFIX/lib/libiconv.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --disable-lz4 --disable-zstd --disable-xxhash --disable-openssl"

    export ac_cv_func_lchmod=no
    export ac_cv_func_lutimes=no
}
termux_step_post_make() {
    mv $TERMUX_PREFIX/lib/hidden_so/* $TERMUX_PREFIX/lib/ 2>/dev/null || true
    rmdir $TERMUX_PREFIX/lib/hidden_so 2>/dev/null || true
}
EOF

# --- NANO PATCH ---
cat << 'EOF' >> packages/nano/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for Nano..."
    mkdir -p $TERMUX_PREFIX/lib/hidden_so
    mv $TERMUX_PREFIX/lib/libncurses*.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true
    mv $TERMUX_PREFIX/lib/libtinfo*.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
}
termux_step_post_make() {
    mv $TERMUX_PREFIX/lib/hidden_so/* $TERMUX_PREFIX/lib/ 2>/dev/null || true
    rmdir $TERMUX_PREFIX/lib/hidden_so 2>/dev/null || true
}
EOF

# --- LESS PATCH ---
cat << 'EOF' >> packages/less/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for Less..."
    mkdir -p $TERMUX_PREFIX/lib/hidden_so
    mv $TERMUX_PREFIX/lib/libncurses*.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true
    mv $TERMUX_PREFIX/lib/libtinfo*.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
}
termux_step_post_make() {
    mv $TERMUX_PREFIX/lib/hidden_so/* $TERMUX_PREFIX/lib/ 2>/dev/null || true
    rmdir $TERMUX_PREFIX/lib/hidden_so 2>/dev/null || true
}
EOF

# Define target architectures: "Termux_Arch:Android_Arch"
ARCHS=("aarch64:arm64-v8a" "arm:armeabi-v7a")

echo "[4/4] Starting compilation process..."
for mapping in "${ARCHS[@]}"; do
    TERMUX_ARCH="${mapping%%:*}"
    ANDROID_ARCH="${mapping##*:}"
    OUT_DIR="$OUTPUT_DIR/jniLibs/$ANDROID_ARCH"
    mkdir -p "$OUT_DIR"

    echo "==================================================================="
    echo "BUILDING STATIC BINARIES FOR: $ANDROID_ARCH ($TERMUX_ARCH)"
    echo "==================================================================="

    # 1. COMPILE PROOT
    if [ -f "$OUT_DIR/libproot.so" ] && [ "$REBUILD_PROOT" -eq 0 ]; then
        echo ">> libproot.so already exists. Skipping."
    else
        echo ">> Building PRoot..."
        FORCE_FLAG=$([ "$REBUILD_PROOT" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" proot

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_proot"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/proot_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/proot "$OUT_DIR/libproot.so"
        cp data/data/com.termux/files/usr/libexec/proot/loader "$OUT_DIR/libproot-loader.so"
        if [ -f data/data/com.termux/files/usr/libexec/proot/loader32 ]; then
            cp data/data/com.termux/files/usr/libexec/proot/loader32 "$OUT_DIR/libproot-loader32.so"
        fi
        cd "$TERMUX_REPO"
    fi

    # 2. COMPILE ARIA2
    if [ -f "$OUT_DIR/libaria2c.so" ] && [ "$REBUILD_ARIA2" -eq 0 ]; then
        echo ">> libaria2c.so already exists. Skipping."
    else
        echo ">> Building Aria2c..."
        FORCE_FLAG=$([ "$REBUILD_ARIA2" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" aria2

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_aria2"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/aria2_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/aria2c "$OUT_DIR/libaria2c.so"
        cd "$TERMUX_REPO"
    fi

    # 3. COMPILE TAR
    if [ -f "$OUT_DIR/libtar.so" ] && [ "$REBUILD_TAR" -eq 0 ]; then
        echo ">> libtar.so already exists. Skipping."
    else
        echo ">> Building GNU Tar..."
        FORCE_FLAG=$([ "$REBUILD_TAR" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" tar

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_tar"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/tar_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/tar "$OUT_DIR/libtar.so"
        cd "$TERMUX_REPO"
    fi

    # 4. COMPILE GZIP
    if [ -f "$OUT_DIR/libgzip.so" ] && [ "$REBUILD_GZIP" -eq 0 ]; then
        echo ">> libgzip.so already exists. Skipping."
    else
        echo ">> Building GNU Gzip..."
        FORCE_FLAG=$([ "$REBUILD_GZIP" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" gzip

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_gzip"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/gzip_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/gzip "$OUT_DIR/libgzip.so"
        cd "$TERMUX_REPO"
    fi

    # 5. COMPILE XZ UTILS
    if [ -f "$OUT_DIR/libxz.so" ] && [ "$REBUILD_XZ" -eq 0 ]; then
        echo ">> libxz.so already exists. Skipping."
    else
        echo ">> Building XZ Utils ($XZ_PKG_NAME)..."
        FORCE_FLAG=$([ "$REBUILD_XZ" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" "$XZ_PKG_NAME"

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_xz"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/xz-utils_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/xz "$OUT_DIR/libxz.so"
        cd "$TERMUX_REPO"
    fi

    # 6. COMPILE RSYNC
    if [ -f "$OUT_DIR/librsync.so" ] && [ "$REBUILD_RSYNC" -eq 0 ]; then
        echo ">> librsync.so already exists. Skipping."
    else
        echo ">> Healing sysroot and Building Rsync..."
        FORCE_FLAG=$([ "$REBUILD_RSYNC" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" libiconv libpopt
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" rsync

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_rsync"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/rsync_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/rsync "$OUT_DIR/librsync.so"
        cd "$TERMUX_REPO"
    fi

    # 7. COMPILE NANO
    if [ -f "$OUT_DIR/libnano.so" ] && [ "$REBUILD_NANO" -eq 0 ]; then
        echo ">> libnano.so already exists. Skipping. Use --rebuild-nano to force."
    else
        echo ">> Healing sysroot and Building Nano..."
        FORCE_FLAG=$([ "$REBUILD_NANO" -eq 1 ] && echo "-f" || echo "")
        # Ncurses must be explicitly built first so we get the .a files
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" ncurses
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" nano

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_nano"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/nano_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/nano "$OUT_DIR/libnano.so"
        cd "$TERMUX_REPO"
    fi

    # 8. COMPILE LESS
    if [ -f "$OUT_DIR/libless.so" ] && [ "$REBUILD_LESS" -eq 0 ]; then
        echo ">> libless.so already exists. Skipping. Use --rebuild-less to force."
    else
        echo ">> Healing sysroot and Building Less..."
        FORCE_FLAG=$([ "$REBUILD_LESS" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" less

        EXTRACT_DIR="$BUILD_DIR/extract_${TERMUX_ARCH}_less"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"; if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/less_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/less "$OUT_DIR/libless.so"
        cd "$TERMUX_REPO"
    fi

    echo ">> Done: $ANDROID_ARCH completed."
done

# ===================================================================
# SUPPLY CHAIN SECURITY & AUTOMATION (SBOM)
# ===================================================================
echo ">> [IIAB] Generating Supply Chain Manifest and Certificates..."

echo "   -> Fetching latest cacert.pem..."
curl -sS -o "$OUTPUT_DIR/cacert.pem" https://curl.se/ca/cacert.pem

MANIFEST_FILE="$OUTPUT_DIR/ninja_manifest.json"
echo "   -> Generating $MANIFEST_FILE..."

cat <<EOF > "$MANIFEST_FILE"
{
  "build_date": "$(date -u +'%Y-%m-%dT%H:%M:%SZ')",
  "builder_info": "IIAB build_static.sh",
  "binaries": {
EOF

FIRST_ARCH=1
for arch_dir in "$OUTPUT_DIR/jniLibs"/*; do
    if [ ! -d "$arch_dir" ]; then continue; fi
    arch_name=$(basename "$arch_dir")

    if [ $FIRST_ARCH -eq 0 ]; then echo "    ," >> "$MANIFEST_FILE"; fi
    FIRST_ARCH=0

    echo "    \"$arch_name\": {" >> "$MANIFEST_FILE"

    FIRST_BIN=1
    for bin_file in "$arch_dir"/*.so; do
        if [ ! -f "$bin_file" ]; then continue; fi
        bin_name=$(basename "$bin_file")

        sha256=$(sha256sum "$bin_file" | awk '{print $1}')
        size=$(stat -c%s "$bin_file" 2>/dev/null || stat -f%z "$bin_file")

        if [ $FIRST_BIN -eq 0 ]; then echo "      ," >> "$MANIFEST_FILE"; fi
        FIRST_BIN=0

        cat <<EOF >> "$MANIFEST_FILE"
      "$bin_name": {
        "sha256": "$sha256",
        "size_bytes": $size
      }
EOF
    done
    echo "    }" >> "$MANIFEST_FILE"
done

cat <<EOF >> "$MANIFEST_FILE"
  }
}
EOF

# ===================================================================
# 9. FIX WEB SERVER PERMISSIONS
# ===================================================================
echo ">> [IIAB] Fixing permissions for web server access..."
sudo chmod -R 755 "$OUTPUT_DIR"

echo ">> [IIAB] Assets prepared successfully!"
echo "==================================================================="
echo "STATIC BUILD SUCCESSFUL!"
echo "Your ninja binaries are ready at: $OUTPUT_DIR/jniLibs/"
echo "Your SBOM and Certs are ready at: $OUTPUT_DIR/"
echo "==================================================================="
