// Resolve PDFium from the bundled binding at runtime. All entry points run on the process-wide PdfiumThread.

#include <android/bitmap.h>
#include <dlfcn.h>
#include <jni.h>
#include <new>
#include <stdint.h>

namespace {
using CountChars = int (*)(void *);
using GetUnicode = unsigned int (*)(void *, int);
using GetFontSize = double (*)(void *, int);
using GetCharOrigin = int (*)(void *, int, double *, double *);

using GetMatrix = int (*)(void *, int, float *);
using GetCharBox = int (*)(void *, int, double *, double *, double *, double *);

struct Pause {
    int version;
    int (*NeedToPauseNow)(Pause *self);
    void *user;
};

using BitmapCreateEx = void *(*)(int width, int height, int format, void *firstScan, int stride);
using BitmapFillRect = void (*)(void *bitmap, int left, int top, int width, int height, unsigned long color);
using BitmapDestroy = void (*)(void *bitmap);
using RenderStart = int (*)(void *bitmap, void *page, int startX, int startY, int sizeX, int sizeY,
                            int rotate, int flags, Pause *pause);
using RenderContinue = int (*)(void *page, Pause *pause);
using RenderClose = void (*)(void *page);

using GetPageBoundingBox = int (*)(void *page, float *rect);
using BookmarkGetFirstChild = void *(*)(void *doc, void *bookmark);
using BookmarkGetNextSibling = void *(*)(void *doc, void *bookmark);

using BookmarkGetTitle = unsigned long (*)(void *bookmark, void *buffer, unsigned long buflen);
using BookmarkGetDest = void *(*)(void *doc, void *bookmark);
using DestGetDestPageIndex = int (*)(void *doc, void *dest);

constexpr int kBitmapBGRx = 3;
constexpr int kBitmapBGRA = 4;
constexpr int kRenderToBeContinued = 1;
constexpr int kRenderDone = 2;

constexpr int kFlagReverseByteOrder = 0x10;

struct Api {
    CountChars countChars = nullptr;
    GetUnicode getUnicode = nullptr;
    GetFontSize getFontSize = nullptr;
    GetCharOrigin getCharOrigin = nullptr;
    GetMatrix getMatrix = nullptr;
    GetCharBox getCharBox = nullptr;
    BitmapCreateEx bitmapCreateEx = nullptr;
    BitmapFillRect bitmapFillRect = nullptr;
    BitmapDestroy bitmapDestroy = nullptr;
    RenderStart renderStart = nullptr;
    RenderContinue renderContinue = nullptr;
    RenderClose renderClose = nullptr;
    GetPageBoundingBox getPageBoundingBox = nullptr;
    BookmarkGetFirstChild bookmarkFirstChild = nullptr;
    BookmarkGetNextSibling bookmarkNextSibling = nullptr;
    BookmarkGetTitle bookmarkTitle = nullptr;
    BookmarkGetDest bookmarkDest = nullptr;
    DestGetDestPageIndex destPageIndex = nullptr;
    bool textOk = false;
    bool renderOk = false;
    bool outlineOk = false;
};

const Api &api() {
    static const Api instance = [] {
        Api a;
        void *lib = dlopen("libpdfium.so", RTLD_NOW | RTLD_NOLOAD);
        if (lib == nullptr) lib = dlopen("libpdfium.so", RTLD_NOW);
        if (lib == nullptr) return a;
        a.countChars = reinterpret_cast<CountChars>(dlsym(lib, "FPDFText_CountChars"));
        a.getUnicode = reinterpret_cast<GetUnicode>(dlsym(lib, "FPDFText_GetUnicode"));
        a.getFontSize = reinterpret_cast<GetFontSize>(dlsym(lib, "FPDFText_GetFontSize"));
        a.getCharOrigin = reinterpret_cast<GetCharOrigin>(dlsym(lib, "FPDFText_GetCharOrigin"));
        a.getMatrix = reinterpret_cast<GetMatrix>(dlsym(lib, "FPDFText_GetMatrix"));
        a.getCharBox = reinterpret_cast<GetCharBox>(dlsym(lib, "FPDFText_GetCharBox"));
        a.textOk = a.countChars && a.getUnicode && a.getFontSize && a.getCharOrigin &&
                   a.getMatrix && a.getCharBox;
        a.bitmapCreateEx = reinterpret_cast<BitmapCreateEx>(dlsym(lib, "FPDFBitmap_CreateEx"));
        a.bitmapFillRect = reinterpret_cast<BitmapFillRect>(dlsym(lib, "FPDFBitmap_FillRect"));
        a.bitmapDestroy = reinterpret_cast<BitmapDestroy>(dlsym(lib, "FPDFBitmap_Destroy"));
        a.renderStart = reinterpret_cast<RenderStart>(dlsym(lib, "FPDF_RenderPageBitmap_Start"));
        a.renderContinue = reinterpret_cast<RenderContinue>(dlsym(lib, "FPDF_RenderPage_Continue"));
        a.renderClose = reinterpret_cast<RenderClose>(dlsym(lib, "FPDF_RenderPage_Close"));
        a.renderOk = a.bitmapCreateEx && a.bitmapFillRect && a.bitmapDestroy &&
                     a.renderStart && a.renderContinue && a.renderClose;
        a.getPageBoundingBox = reinterpret_cast<GetPageBoundingBox>(dlsym(lib, "FPDF_GetPageBoundingBox"));
        a.bookmarkFirstChild = reinterpret_cast<BookmarkGetFirstChild>(dlsym(lib, "FPDFBookmark_GetFirstChild"));
        a.bookmarkNextSibling = reinterpret_cast<BookmarkGetNextSibling>(dlsym(lib, "FPDFBookmark_GetNextSibling"));
        a.bookmarkTitle = reinterpret_cast<BookmarkGetTitle>(dlsym(lib, "FPDFBookmark_GetTitle"));
        a.bookmarkDest = reinterpret_cast<BookmarkGetDest>(dlsym(lib, "FPDFBookmark_GetDest"));
        a.destPageIndex = reinterpret_cast<DestGetDestPageIndex>(dlsym(lib, "FPDFDest_GetDestPageIndex"));
        a.outlineOk = a.bookmarkFirstChild && a.bookmarkNextSibling && a.bookmarkTitle &&
                      a.bookmarkDest && a.destPageIndex;
        return a;
    }();
    return instance;
}

// Must match PdfiumNative.STRIDE.
constexpr int kStride = 12;

constexpr unsigned long kTitleMaxBytes = 256 * 1024;
constexpr jsize kTitleMaxChars = 512;

// Must match PdfiumNative.RENDER_*.
constexpr int kResultDone = 0;
constexpr int kResultCancelled = 1;
constexpr int kResultFailed = 2;
constexpr int kResultUnavailable = 3;

// pdfiumandroid 2.0.3 exposes DocumentFile*, whose first member is FPDF_DOCUMENT; recheck on upgrades.
void *fpdfDocument(jlong documentFile) {
    if (documentFile == 0) return nullptr;
    return *reinterpret_cast<void **>(documentFile);
}

struct CancelProbe {
    JNIEnv *env;
    jintArray flag;
};

int needToPauseNow(Pause *self) {
    auto *probe = static_cast<CancelProbe *>(self->user);
    jint value = 0;
    probe->env->GetIntArrayRegion(probe->flag, 0, 1, &value);
    return value != 0;
}

// Shared scratch is thread-confined to PdfiumThread; the Kotlin pixel budget bounds its size.
uint32_t *scratch = nullptr;
size_t scratchPixels = 0;

uint32_t *scratchFor(size_t pixels) {
    if (scratchPixels >= pixels) return scratch;
    uint32_t *grown = new (std::nothrow) uint32_t[pixels];
    if (grown == nullptr) return nullptr;
    delete[] scratch;
    scratch = grown;
    scratchPixels = pixels;
    return scratch;
}

}

// Per character: codepoint, origin x/y, font size, matrix a/b/c/d, box left/right/bottom/top.
// Coordinates are unrotated PDF user space; missing geometry uses neutral values.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_readChars(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong textPage,
    jint maxChars
) {
    auto fail = [env](const char *message) -> jfloatArray {
        jclass type = env->FindClass("java/io/IOException");
        if (type != nullptr) {
            env->ThrowNew(type, message);
            env->DeleteLocalRef(type);
        }
        return nullptr;
    };
    const Api &a = api();
    if (!a.textOk || textPage == 0) return fail("Page text is unavailable");
    void *page = reinterpret_cast<void *>(textPage);
    int count = a.countChars(page);
    if (count < 0) return fail("Failed to read page text");
    if (count > maxChars) return fail("Page text exceeds extraction limit");

    jfloatArray out = env->NewFloatArray(count * kStride);
    if (out == nullptr || count == 0) return out;
    jfloat *buf = env->GetFloatArrayElements(out, nullptr);
    if (buf == nullptr) return nullptr;

    for (int i = 0; i < count; i++) {
        jfloat *c = buf + i * kStride;
        c[0] = static_cast<jfloat>(a.getUnicode(page, i));
        double x = 0, y = 0;
        if (!a.getCharOrigin(page, i, &x, &y)) { x = 0; y = 0; }
        c[1] = static_cast<jfloat>(x);
        c[2] = static_cast<jfloat>(y);
        c[3] = static_cast<jfloat>(a.getFontSize(page, i));
        float m[6] = {1, 0, 0, 1, 0, 0};
        if (!a.getMatrix(page, i, m)) { m[0] = 1; m[1] = 0; m[2] = 0; m[3] = 1; }
        c[4] = m[0];
        c[5] = m[1];
        c[6] = m[2];
        c[7] = m[3];
        double l = 0, r = 0, b = 0, t = 0;
        if (!a.getCharBox(page, i, &l, &r, &b, &t)) { l = r = b = t = 0; }
        c[8] = static_cast<jfloat>(l);
        c[9] = static_cast<jfloat>(r);
        c[10] = static_cast<jfloat>(b);
        c[11] = static_cast<jfloat>(t);
    }
    env->ReleaseFloatArrayElements(out, buf, 0);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_render(
    JNIEnv *env,
    jobject /*thiz*/,
    jlong pagePtr,
    jobject bitmap,
    jint startX,
    jint startY,
    jint sizeX,
    jint sizeY,
    jint flags,
    jintArray cancel
) {
    const Api &a = api();
    if (!a.renderOk || pagePtr == 0 || bitmap == nullptr) return kResultUnavailable;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return kResultFailed;
    const bool direct = info.format == ANDROID_BITMAP_FORMAT_RGBA_8888;
    const bool to565 = info.format == ANDROID_BITMAP_FORMAT_RGB_565;
    if (!direct && !to565) return kResultUnavailable;
    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return kResultFailed;

    uint32_t *temp = nullptr;
    void *target = pixels;
    int stride = static_cast<int>(info.stride);
    int format = kBitmapBGRA;
    int renderFlags = flags | kFlagReverseByteOrder;
    if (to565) {
        temp = scratchFor(static_cast<size_t>(width) * height);
        if (temp == nullptr) {
            AndroidBitmap_unlockPixels(env, bitmap);
            return kResultFailed;
        }
        target = temp;
        stride = width * 4;

        format = kBitmapBGRx;
        renderFlags = flags;
    }

    int result = kResultFailed;
    void *fpdfBitmap = a.bitmapCreateEx(width, height, format, target, stride);
    if (fpdfBitmap != nullptr) {
        a.bitmapFillRect(fpdfBitmap, 0, 0, width, height, 0xFFFFFFFFul);
        CancelProbe probe{env, cancel};
        Pause pause{1, needToPauseNow, &probe};
        void *page = reinterpret_cast<void *>(pagePtr);
        int state = a.renderStart(fpdfBitmap, page, startX, startY, sizeX, sizeY, 0, renderFlags, &pause);
        while (state == kRenderToBeContinued) {
            if (needToPauseNow(&pause)) break;
            state = a.renderContinue(page, &pause);
        }
        // Close the progressive render context even after failure or cancellation.
        a.renderClose(page);
        result = state == kRenderDone ? kResultDone
               : state == kRenderToBeContinued ? kResultCancelled
               : kResultFailed;
        a.bitmapDestroy(fpdfBitmap);
    }
    if (to565 && result == kResultDone) {
        for (int y = 0; y < height; y++) {
            const uint8_t *src = reinterpret_cast<const uint8_t *>(temp + static_cast<size_t>(y) * width);
            uint16_t *dst = reinterpret_cast<uint16_t *>(static_cast<uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride);
            for (int x = 0; x < width; x++, src += 4) {
                const uint32_t b = src[0], g = src[1], r = src[2];
                dst[x] = static_cast<uint16_t>(((r & 0xF8u) << 8) | ((g & 0xFCu) << 3) | (b >> 3));
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_pageBounds(JNIEnv *env, jobject /*thiz*/, jlong pagePtr) {
    const Api &a = api();
    if (a.getPageBoundingBox == nullptr || pagePtr == 0) return nullptr;
    float rect[4] = {0, 0, 0, 0};
    if (!a.getPageBoundingBox(reinterpret_cast<void *>(pagePtr), rect)) return nullptr;
    jfloatArray out = env->NewFloatArray(4);
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, 4, rect);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_bookmarkFirstChild(JNIEnv *, jobject, jlong doc, jlong bookmark) {
    const Api &a = api();
    void *d = fpdfDocument(doc);
    if (!a.outlineOk || d == nullptr) return 0;
    return reinterpret_cast<jlong>(a.bookmarkFirstChild(d, reinterpret_cast<void *>(bookmark)));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_bookmarkNextSibling(JNIEnv *, jobject, jlong doc, jlong bookmark) {
    const Api &a = api();
    void *d = fpdfDocument(doc);
    if (!a.outlineOk || d == nullptr || bookmark == 0) return 0;
    return reinterpret_cast<jlong>(a.bookmarkNextSibling(d, reinterpret_cast<void *>(bookmark)));
}

// PDFium writes nothing to undersized title buffers; allocate the full UTF-16LE result before truncating.
extern "C" JNIEXPORT jstring JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_bookmarkTitle(JNIEnv *env, jobject, jlong bookmark) {
    const Api &a = api();
    if (!a.outlineOk || bookmark == 0) return nullptr;
    void *bm = reinterpret_cast<void *>(bookmark);
    const unsigned long bytes = a.bookmarkTitle(bm, nullptr, 0);
    if (bytes < 2) return env->NewString(nullptr, 0);
    if (bytes > kTitleMaxBytes) {
        static const jchar ellipsis[] = {0x2026};
        return env->NewString(ellipsis, 1);
    }
    auto *buffer = new (std::nothrow) uint16_t[bytes / 2];
    if (buffer == nullptr) return nullptr;
    jstring out = nullptr;
    if (a.bookmarkTitle(bm, buffer, bytes) == bytes) {
        jsize chars = static_cast<jsize>(bytes / 2);

        while (chars > 0 && buffer[chars - 1] == 0) chars--;
        if (chars > kTitleMaxChars) chars = kTitleMaxChars;
        out = env->NewString(reinterpret_cast<const jchar *>(buffer), chars);
    }
    delete[] buffer;
    return out;
}

// FPDFBookmark_GetDest also resolves /A GoTo actions when /Dest is absent.
extern "C" JNIEXPORT jint JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_bookmarkPage(JNIEnv *, jobject, jlong doc, jlong bookmark) {
    const Api &a = api();
    void *d = fpdfDocument(doc);
    if (!a.outlineOk || d == nullptr || bookmark == 0) return -1;
    void *dest = a.bookmarkDest(d, reinterpret_cast<void *>(bookmark));
    if (dest == nullptr) return -1;
    return a.destPageIndex(d, dest);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stb6_spdf_pdf_PdfiumNative_releaseScratch(JNIEnv *, jobject) {
    delete[] scratch;
    scratch = nullptr;
    scratchPixels = 0;
}
