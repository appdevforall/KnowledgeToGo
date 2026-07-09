package org.iiab.controller.feedback.domain;

/**
 * Immutable, transport-agnostic description of a single feedback or crash report.
 *
 * <p>Pure domain type (no Android, no I/O). It is rendered to an email by
 * {@link FeedbackRenderer} today; the same fields map 1:1 to the JSON a future HTTPS
 * endpoint (Cloudflare Worker) will accept, so adding that transport is additive and
 * does not change this type.
 *
 * <p>Required: {@code type}, {@code appVersion}, {@code appBuild}, {@code androidRelease},
 * {@code device}, {@code abi}. Everything else is optional and is omitted from the
 * rendered output when absent.
 */
public final class FeedbackPayload {

    private final FeedbackType type;
    private final String message;
    private final String contactEmail;
    private final String appVersion;
    private final int appBuild;
    private final String androidRelease;
    private final String device;
    private final String abi;
    private final String rootfsBuild;
    private final RootfsChecksumStatus rootfsChecksum;
    private final String binariesTag;
    private final String screen;
    private final String stackTrace;
    private final String screenshot;

    private FeedbackPayload(Builder b) {
        if (b.type == null) {
            throw new IllegalArgumentException("type is required");
        }
        this.type = b.type;
        this.message = b.message == null ? "" : b.message;
        this.contactEmail = b.contactEmail;
        this.appVersion = b.appVersion;
        this.appBuild = b.appBuild;
        this.androidRelease = b.androidRelease;
        this.device = b.device;
        this.abi = b.abi;
        this.rootfsBuild = b.rootfsBuild;
        this.rootfsChecksum = b.rootfsChecksum;
        this.binariesTag = b.binariesTag;
        this.screen = b.screen;
        this.stackTrace = b.stackTrace;
        this.screenshot = b.screenshot;
    }

    public FeedbackType type() { return type; }

    public String message() { return message; }

    public String contactEmail() { return contactEmail; }

    public String appVersion() { return appVersion; }

    public int appBuild() { return appBuild; }

    public String androidRelease() { return androidRelease; }

    public String device() { return device; }

    public String abi() { return abi; }

    public String rootfsBuild() { return rootfsBuild; }

    public RootfsChecksumStatus rootfsChecksum() { return rootfsChecksum; }

    public String binariesTag() { return binariesTag; }

    public String screen() { return screen; }

    public String stackTrace() { return stackTrace; }

    /** Absolute path to an optional screenshot to attach (local file). */
    public String screenshot() { return screenshot; }

    public static Builder builder(FeedbackType type) {
        return new Builder(type);
    }

    /** Fluent builder; only {@code type} is mandatory. */
    public static final class Builder {
        private final FeedbackType type;
        private String message;
        private String contactEmail;
        private String appVersion;
        private int appBuild;
        private String androidRelease;
        private String device;
        private String abi;
        private String rootfsBuild;
        private RootfsChecksumStatus rootfsChecksum;
        private String binariesTag;
        private String screen;
        private String stackTrace;
        private String screenshot;

        private Builder(FeedbackType type) {
            this.type = type;
        }

        public Builder message(String v) { this.message = v; return this; }

        public Builder contactEmail(String v) { this.contactEmail = v; return this; }

        public Builder appVersion(String v) { this.appVersion = v; return this; }

        public Builder appBuild(int v) { this.appBuild = v; return this; }

        public Builder androidRelease(String v) { this.androidRelease = v; return this; }

        public Builder device(String v) { this.device = v; return this; }

        public Builder abi(String v) { this.abi = v; return this; }

        public Builder rootfsBuild(String v) { this.rootfsBuild = v; return this; }

        public Builder rootfsChecksum(RootfsChecksumStatus v) { this.rootfsChecksum = v; return this; }

        public Builder binariesTag(String v) { this.binariesTag = v; return this; }

        public Builder screen(String v) { this.screen = v; return this; }

        public Builder stackTrace(String v) { this.stackTrace = v; return this; }

        public Builder screenshot(String v) { this.screenshot = v; return this; }

        public FeedbackPayload build() {
            return new FeedbackPayload(this);
        }
    }
}
