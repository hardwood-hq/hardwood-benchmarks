/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.benchmarks.nested;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Downloads an Overture Maps places Parquet file for nested schema performance testing.
///
/// The download URL is resolved at runtime from the public STAC catalog at
/// `https://stac.overturemaps.org/`, so the downloader always fetches the current
/// release rather than relying on a hard-coded identifier that may have been rotated
/// out of the S3 bucket.
public final class OvertureMapsDownloader {

    private static final String STAC_ROOT = "https://stac.overturemaps.org/catalog.json";
    private static final String STAC_ITEM_TEMPLATE =
            "https://stac.overturemaps.org/%s/places/place/00000/00000.json";

    private static final Pattern LATEST_RELEASE_PATTERN =
            Pattern.compile("\"latest\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AWS_HREF_PATTERN =
            Pattern.compile("\"aws\"\\s*:\\s*\\{[^}]*?\"href\"\\s*:\\s*\"(https://[^\"]+\\.parquet)\"");

    private static final String TARGET_FILENAME = "overture_places.zstd.parquet";

    /// Reported by [#releaseOf(Path)] when no `.release` sidecar accompanies the file.
    public static final String UNKNOWN_RELEASE = "unknown";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /// Ensures the Overture Maps places file exists at `target`, downloading the
    /// current release to it if absent, and returns it. Used by the benchmark's
    /// `@Setup` so the nested benchmark self-seeds its data like the flat and
    /// filtered ones.
    ///
    /// A download also writes the resolved release identifier to a `.release`
    /// sidecar beside `target`, so [#releaseOf(Path)] can report which release a
    /// measurement ran on. The catalog serves only the most recent releases, so a
    /// published number cannot be re-derived from the file alone once its release
    /// has rotated out of the bucket.
    public static Path ensure(Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            String release = resolveLatestRelease();
            downloadFile(resolvePlacesUrl(release), target);
            Files.writeString(releaseSidecar(target), release + "\n");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading Overture Maps places file to " + target, e);
        }
        return target;
    }

    /// The Overture release `target` was downloaded from, read from the `.release`
    /// sidecar [#ensure(Path)] writes, or [#UNKNOWN_RELEASE] when there is none —
    /// the file was supplied by hand (`--file`) or predates the sidecar.
    public static String releaseOf(Path target) {
        Path sidecar = releaseSidecar(target);
        if (!Files.isRegularFile(sidecar)) {
            return UNKNOWN_RELEASE;
        }
        try {
            String release = Files.readString(sidecar).strip();
            return release.isEmpty() ? UNKNOWN_RELEASE : release;
        }
        catch (IOException e) {
            return UNKNOWN_RELEASE;
        }
    }

    private static Path releaseSidecar(Path target) {
        return target.resolveSibling(target.getFileName() + ".release");
    }

    public static void main(String[] args) throws IOException {
        Path target = getDataDirFromProperty().resolve(TARGET_FILENAME);
        boolean present = Files.exists(target) && Files.size(target) > 0;
        ensure(target);
        System.out.println((present ? "Overture Maps file already exists: " : "Download complete. File at: ")
                + target.toAbsolutePath() + " (" + Files.size(target) + " bytes, release "
                + releaseOf(target) + ")");
    }

    private static Path getDataDirFromProperty() {
        String property = System.getProperty("data.dir");
        if (property == null || property.isBlank()) {
            return Path.of("target/overture-maps-data");
        }
        return Path.of(property);
    }

    private static String resolveLatestRelease() throws IOException, InterruptedException {
        String root = fetchString(STAC_ROOT);
        String release = extract(LATEST_RELEASE_PATTERN, root, STAC_ROOT, "latest");
        System.out.println("Resolved latest Overture release: " + release);
        return release;
    }

    private static String resolvePlacesUrl(String release) throws IOException, InterruptedException {
        String itemUrl = String.format(STAC_ITEM_TEMPLATE, release);
        String item = fetchString(itemUrl);
        String href = extract(AWS_HREF_PATTERN, item, itemUrl, "assets.aws.href");
        System.out.println("Resolved places Parquet URL: " + href);
        return href;
    }

    private static String fetchString(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected status " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private static String extract(Pattern pattern, String body, String sourceUrl, String field)
            throws IOException {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            String excerpt = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new IOException("Could not find '" + field + "' in STAC document at "
                    + sourceUrl + "; body starts with: " + excerpt);
        }
        return matcher.group(1);
    }

    /// Download `url` to `target`, via a `.part` file that is moved into place only
    /// once the transfer completed. `ensure` treats any non-empty file at `target`
    /// as the dataset, so a process killed mid-transfer must not leave one there:
    /// the next run would benchmark a truncated file.
    private static void downloadFile(String url, Path target) throws IOException, InterruptedException {
        System.out.println("Downloading: " + url);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        try {
            HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) {
                throw new IOException("Download failed with status " + response.statusCode() + " for " + url);
            }
            if (Files.size(part) == 0) {
                throw new IOException("Download produced an empty file for " + url);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException | InterruptedException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private OvertureMapsDownloader() {
    }
}
