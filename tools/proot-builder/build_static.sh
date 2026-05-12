#!/bin/bash
# -----------------------------------------------------------------------------
# Static Builder for Android (Multi-Architecture)
# Compiles self-contained static binaries for PRoot, Aria2c, Tar, and Gzip
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

for arg in "$@"; do
    case $arg in
        --rebuild-all)
            REBUILD_PROOT=1
            REBUILD_ARIA2=1
            REBUILD_TAR=1
            REBUILD_GZIP=1
            REBUILD_RSYNC=1
            shift
            ;;
        --rebuild-proot)
            REBUILD_PROOT=1
            shift
            ;;
        --rebuild-aria2)
            REBUILD_ARIA2=1
            shift
            ;;
        --rebuild-tar)
            REBUILD_TAR=1
            shift
            ;;
        --rebuild-gzip)
            REBUILD_GZIP=1
            shift
            ;;
        --rebuild-rsync)
            REBUILD_RSYNC=1
            shift
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $0 [--rebuild-all | --rebuild-proot | --rebuild-aria2 | --rebuild-tar | --rebuild-gzip | --rebuild-all]"
            exit 1
            ;;
    esac
done

WORK_DIR=$(pwd)
TERMUX_REPO="$WORK_DIR/termux-packages"

echo "[1/4] Checking host dependencies..."
if ! command -v docker &> /dev/null; then 
    echo "ERROR: Docker is missing. Please install Docker to proceed."
    exit 1
fi

if ! command -v patchelf &> /dev/null; then
    sudo apt-get update
    sudo apt-get install -y patchelf binutils wget tar xz-utils
fi

echo "[2/4] Preparing termux-packages environment..."
if [ ! -d "$TERMUX_REPO" ]; then
    git clone --depth 1 https://github.com/termux/termux-packages.git "$TERMUX_REPO"
else
    cd "$TERMUX_REPO"
    git restore .
    git pull
    cd ..
fi

echo "[3/4] Patching build scripts for static compilation..."
cd "$TERMUX_REPO"

# --- PROOT PATCH ---
sed -i 's/"libtalloc"/"libtalloc-static, libtalloc"/' packages/proot/build.sh
sed -i '/termux_step_pre_configure()/a \tLDFLAGS+=" -static"\n\tLDFLAGS+=" -ffunction-sections -fdata-sections -Wl,--gc-sections"' packages/proot/build.sh

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

    echo ">> [IIAB] Lobotomizing OpenSSL Legacy Provider crash..."
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
# Inject dependencies tricking the Python script with sed
sed -i -E 's/libandroid-glob/libandroid-glob-static, libandroid-glob/g' packages/tar/build.sh
sed -i -E 's/libiconv/libiconv-static, libiconv/g' packages/tar/build.sh

# Force static linking for for GNU Tar
cat << 'EOF' >> packages/tar/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for GNU Tar..."

    # Destroy dynamic libs to force .a usage
    rm -f $TERMUX_PREFIX/lib/libandroid-glob.so*
    rm -f $TERMUX_PREFIX/lib/libiconv.so*

    # IIAB Static Build, Clang 16 bypass and Duplicate Tolerance
    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,--allow-multiple-definition"
    CFLAGS+=" -Wno-error=implicit-function-declaration -Dlchmod=chmod"
    export gl_cv_func_lchown_works=yes

    # Y2038 Bug bypass for 32-bit processors
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --disable-year2038"
}
EOF

# --- GZIP PATCH ---
# Force static linking for GNU Gzip
cat << 'EOF' >> packages/gzip/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for GNU Gzip..."
    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"

    # Force Gnulib to compile its own qsort_r by denying the system's one
    export ac_cv_func_qsort_r=no
    export gl_cv_func_qsort_r=no
}
EOF

# --- RSYNC PATCH ---
cat << 'EOF' >> packages/rsync/build.sh
termux_step_pre_configure() {
    echo ">> Applying Static flags for Rsync..."
    
    # temporal: Escondemos los .so en lugar de borrarlos
    mkdir -p $TERMUX_PREFIX/lib/hidden_so
    mv $TERMUX_PREFIX/lib/libpopt.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true
    mv $TERMUX_PREFIX/lib/libiconv.so* $TERMUX_PREFIX/lib/hidden_so/ 2>/dev/null || true

    LDFLAGS+=" -static -ffunction-sections -fdata-sections -Wl,--gc-sections"
    
    # temporal: Desactivamos compresiones modernas
    TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" --disable-lz4 --disable-zstd --disable-xxhash --disable-openssl"

    # temporal: Mentimos al compilador para evitar los errores de cabeceras de Android (Bionic libc)
    export ac_cv_func_lchmod=no
    export ac_cv_func_lutimes=no
}

termux_step_post_make() {
    # temporal: Restauramos las librerías dinámicas a su lugar original
    mv $TERMUX_PREFIX/lib/hidden_so/* $TERMUX_PREFIX/lib/ 2>/dev/null || true
    rmdir $TERMUX_PREFIX/lib/hidden_so 2>/dev/null || true
}
EOF

# Define target architectures: "Termux_Arch:Android_Arch"
ARCHS=("aarch64:arm64-v8a" "arm:armeabi-v7a")

for mapping in "${ARCHS[@]}"; do
    TERMUX_ARCH="${mapping%%:*}"
    ANDROID_ARCH="${mapping##*:}"
    OUT_DIR="$WORK_DIR/dist/jniLibs/$ANDROID_ARCH"
    mkdir -p "$OUT_DIR"

    echo "==================================================================="
    echo "BUILDING STATIC BINARIES FOR: $ANDROID_ARCH ($TERMUX_ARCH)"
    echo "==================================================================="

    # -------------------------------------------------------------------------
    # 1. COMPILE PROOT
    # -------------------------------------------------------------------------
    if [ -f "$OUT_DIR/libproot.so" ] && [ "$REBUILD_PROOT" -eq 0 ]; then
        echo ">> libproot.so already exists. Skipping. Use --rebuild-proot to force."
    else
        echo ">> Building PRoot..."
        FORCE_FLAG=$([ "$REBUILD_PROOT" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" proot

        EXTRACT_DIR="$WORK_DIR/extract_${TERMUX_ARCH}_proot"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

		# Extract PRoot and its Loaders!
		rm -rf data control
		ar x "$DEB_DIR/proot_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
		OUT_DIR="$WORK_DIR/dist/jniLibs/$ANDROID_ARCH"
		mkdir -p "$OUT_DIR"

		cp data/data/com.termux/files/usr/bin/proot "$OUT_DIR/libproot.so"
		cp data/data/com.termux/files/usr/libexec/proot/loader "$OUT_DIR/libproot-loader.so"

		# 32-bit loader (required for some architectures)
		if [ -f data/data/com.termux/files/usr/libexec/proot/loader32 ]; then
			cp data/data/com.termux/files/usr/libexec/proot/loader32 "$OUT_DIR/libproot-loader32.so"
		fi
		cd "$TERMUX_REPO"
    fi

    # -------------------------------------------------------------------------
    # 2. COMPILE ARIA2
    # -------------------------------------------------------------------------
    if [ -f "$OUT_DIR/libaria2c.so" ] && [ "$REBUILD_ARIA2" -eq 0 ]; then
        echo ">> libaria2c.so already exists. Skipping. Use --rebuild-aria2 to force."
    else
        echo ">> Building Aria2c..."
        FORCE_FLAG=$([ "$REBUILD_ARIA2" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" aria2

        EXTRACT_DIR="$WORK_DIR/extract_${TERMUX_ARCH}_aria2"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/aria2_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/aria2c "$OUT_DIR/libaria2c.so"
        cd "$TERMUX_REPO"
    fi

    # -------------------------------------------------------------------------
    # 3. COMPILE TAR
    # -------------------------------------------------------------------------
    if [ -f "$OUT_DIR/libtar.so" ] && [ "$REBUILD_TAR" -eq 0 ]; then
        echo ">> libtar.so already exists. Skipping. Use --rebuild-tar to force."
    else
        echo ">> Building GNU Tar..."
        FORCE_FLAG=$([ "$REBUILD_TAR" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" tar

        EXTRACT_DIR="$WORK_DIR/extract_${TERMUX_ARCH}_tar"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/tar_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        cp data/data/com.termux/files/usr/bin/tar "$OUT_DIR/libtar.so"
        cd "$TERMUX_REPO"
    fi

    # -------------------------------------------------------------------------
    # 4. COMPILE GZIP
    # -------------------------------------------------------------------------
    if [ -f "$OUT_DIR/libgzip.so" ] && [ "$REBUILD_GZIP" -eq 0 ]; then
        echo ">> libgzip.so already exists. Skipping. Use --rebuild-gzip to force."
    else
        echo ">> Building GNU Gzip..."
        FORCE_FLAG=$([ "$REBUILD_GZIP" -eq 1 ] && echo "-f" || echo "")
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" gzip

        EXTRACT_DIR="$WORK_DIR/extract_${TERMUX_ARCH}_gzip"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/gzip_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz
        # Ensure we package it as .so so Android allows its execution
        cp data/data/com.termux/files/usr/bin/gzip "$OUT_DIR/libgzip.so"
        cd "$TERMUX_REPO"
    fi

    # -------------------------------------------------------------------------
    # 5. COMPILE RSYNC
    # -------------------------------------------------------------------------
    if [ -f "$OUT_DIR/librsync.so" ] && [ "$REBUILD_RSYNC" -eq 0 ]; then
        echo ">> librsync.so already exists. Skipping. Use --rebuild-rsync to force."
    else
        echo ">> Healing sysroot and Building Rsync..."
        FORCE_FLAG=$([ "$REBUILD_RSYNC" -eq 1 ] && echo "-f" || echo "")

        # temporal: Forzamos la recompilación de las librerías que rompimos antes para sanar el entorno
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" libiconv libpopt

        # Ahora sí, compilamos rsync
        ./scripts/run-docker.sh ./build-package.sh $FORCE_FLAG -a "$TERMUX_ARCH" rsync

        EXTRACT_DIR="$WORK_DIR/extract_${TERMUX_ARCH}_rsync"
        mkdir -p "$EXTRACT_DIR" && cd "$EXTRACT_DIR"
        DEB_DIR="$TERMUX_REPO/output"
        if [ ! -d "$DEB_DIR" ]; then DEB_DIR="$TERMUX_REPO/debs"; fi

        rm -rf data control
        ar x "$DEB_DIR/rsync_"*"_$TERMUX_ARCH.deb" && tar xf data.tar.xz

        # temporal: Copiamos el binario y lo renombramos a .so
        cp data/data/com.termux/files/usr/bin/rsync "$OUT_DIR/librsync.so"
        cd "$TERMUX_REPO"
    fi

    echo ">> Done: $ANDROID_ARCH completed."
done

echo "==================================================================="
echo "STATIC BUILD SUCCESSFUL!"
echo "Your ninja binaries are ready at: $WORK_DIR/dist/jniLibs/"
echo "==================================================================="
