package moe.shizuku.manager.xion

/**
 * Canonical outbound links for this fork. Every project link in the app points here so a tap
 * always lands on the project the build actually came from, and the branding always resolves
 * to the author's profile.
 */
object XionLinks {

    /** The author profile; the brand mark in the UI opens this. */
    const val PROFILE = "https://github.com/XiONDiGiTaL"

    /** This project's repository. */
    const val REPO = "https://github.com/XiONDiGiTaL/Shizuku"

    const val RELEASES = "$REPO/releases"
    const val LATEST_RELEASE = "$REPO/releases/latest"
    const val ISSUES = "$REPO/issues"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val WIKI = "$REPO/wiki"

    /** GitHub API endpoint used by the in-app updater. */
    const val RELEASES_API = "https://api.github.com/repos/XiONDiGiTaL/Shizuku/releases"
}
