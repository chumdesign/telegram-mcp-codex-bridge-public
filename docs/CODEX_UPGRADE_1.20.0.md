# Codex bridge upgrade to upstream 1.20.0

The upgrade is a Git merge of tolboy/telegram-mcp-tdlib tag v1.20.0 (6b8559f),
not a version-label change. A separate pre-upgrade checkpoint retains all local
customizations. The existing user's installed service/configuration is separate.

Dependencies: TDLight 3.5.9 + TDLib 1.8.68; MCP SDK 2.1.0; Gradle 9.8.2; JDK 25.

Codex-specific functions are isolated in CodexTdLibAdapter and use the upstream
TdLibClientSupport request executor: shared flood gate, deadlines, cancellation,
connection health, final-delivery tracker and album reconciliation. Native Article
and media sends use staged upload snapshots and optional send-operation receipts.

The 12 additional tools remain: send_article, edit_article, validate_article,
get_article, edit_photo_post, send_media_album, find_custom_emoji,
ensure_unread_private_folder, inspect_chat_folder_rules, inspect_channel_activity,
inspect_group_upgrade and upgrade_group_to_supergroup.

Native tables remain PageBlockTable cells. The new isCompact field is exposed as
compact on block input, read back as isCompact, and preserved on in-place edits.
No user-account Article path relies on bot-only Markdown/HTML source objects.

Personal-mode ordinary writes remain open. Dangerous and privacy-changing calls
require native human approval on Windows/macOS. Startup/initialize never opens
an approval dialog. The optional upstream permissions, mutation scope and output
contracts include reviewed Article/media operations; session files stay protected.

Portable kits share one daemon (default loopback port 17433) using startup and
daemon file locks. M1 and M4 both use macos-arm64. Actual Mac execution requires
recipient acceptance; no Mac hardware is available on the build machine.

Build/test results and exact artifact hashes are recorded in each kit's
VERIFICATION.json, manifest.json and SHA256SUMS. No account/session is packaged.

Windows test portability: ACL fixtures resolve BUILTIN\Users by well-known SID,
start from private file/parent ACLs, and remove directory symlinks before their
targets during cleanup. The late-startup probe has a larger test-only watchdog
budget for a loaded Windows runner. Production security checks and shutdown
deadlines are unchanged. Use a private temporary directory for the test process.
