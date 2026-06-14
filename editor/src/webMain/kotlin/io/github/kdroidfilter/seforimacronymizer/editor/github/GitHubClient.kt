package io.github.kdroidfilter.seforimacronymizer.editor.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class GitHubClient(
    private val owner: String = "kdroidFilter",
    private val repo: String = "SeforimAcronymizer",
    private val tokenProvider: () -> String,
) {
    private val api = "https://api.github.com/repos/$owner/$repo"

    private val http = HttpClient(Js) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun HttpRequestBuilder.auth() {
        val token = tokenProvider()
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /** Latest published release tag (no auth required for public repos). */
    suspend fun latestReleaseTag(): String? =
        runCatching { http.get("$api/releases/latest") { auth() }.body<ReleaseInfo>().tag_name }.getOrNull()

    suspend fun defaultBranch(): String =
        http.get(api) { auth() }.body<RepoInfo>().default_branch

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun proposePr(dump: String, title: String, body: String, branchName: String): String {
        val base = defaultBranch()
        val baseSha = http.get("$api/git/ref/heads/$base") { auth() }.body<RefObject>().`object`.sha
        val baseTree = http.get("$api/git/commits/$baseSha") { auth() }.body<CommitObject>().tree.sha

        val blobSha = http.post("$api/git/blobs") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CreateBlob(Base64.encode(dump.encodeToByteArray()), "base64"))
        }.body<ShaObj>().sha

        val treeSha = http.post("$api/git/trees") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CreateTree(baseTree, listOf(TreeEntry("data/acronymizer.sql", "100644", "blob", blobSha))))
        }.body<ShaObj>().sha

        val commitSha = http.post("$api/git/commits") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CreateCommit(title, treeSha, listOf(baseSha)))
        }.body<ShaObj>().sha

        http.post("$api/git/refs") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CreateRef("refs/heads/$branchName", commitSha))
        }

        return http.post("$api/pulls") {
            auth(); contentType(ContentType.Application.Json)
            setBody(CreatePull(title, branchName, base, body))
        }.body<PullResponse>().html_url
    }
}

@Serializable
private data class ReleaseInfo(val tag_name: String)

@Serializable
private data class RepoInfo(val default_branch: String)

@Serializable
private data class ShaObj(val sha: String)

@Serializable
private data class RefObject(val `object`: ShaObj)

@Serializable
private data class CommitObject(val tree: ShaObj)

@Serializable
private data class CreateBlob(val content: String, val encoding: String)

@Serializable
private data class TreeEntry(val path: String, val mode: String, val type: String, val sha: String)

@Serializable
private data class CreateTree(val base_tree: String, val tree: List<TreeEntry>)

@Serializable
private data class CreateCommit(val message: String, val tree: String, val parents: List<String>)

@Serializable
private data class CreateRef(val ref: String, val sha: String)

@Serializable
private data class CreatePull(val title: String, val head: String, val base: String, val body: String)

@Serializable
private data class PullResponse(val html_url: String)
