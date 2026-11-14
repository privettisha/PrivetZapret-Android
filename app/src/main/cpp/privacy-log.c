/* Upstream ANDROID_APP logs destinations and TLS names unconditionally.
 * Do not send browsing metadata to logcat, including in debug APKs.
 * Service lifecycle failures are surfaced by the Kotlin service without URLs.
 */
int __wrap___android_log_print(int priority, const char *tag, const char *format, ...) {
    (void) priority;
    (void) tag;
    (void) format;
    return 0;
}
