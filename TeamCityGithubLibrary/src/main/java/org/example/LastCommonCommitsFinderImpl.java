package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class LastCommonCommitsFinderImpl implements LastCommonCommitsFinder {
    private static final String GITHUB_GRAPHQL_API_URL = "https://api.github.com/graphql";
    private static final int MAX_COMMITS = 1000;
    private final String owner;
    private final String repo;
    private final String token;
    private final Gson gson = new Gson();
    private final Map<String, CommitNode> commitCache = new HashMap<>();
    private final HttpClient client;

    public LastCommonCommitsFinderImpl(String owner, String repo, String token) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public Collection<String> findLastCommonCommits(String branchA, String branchB) throws IOException {
        Map<String, CommitNode> commitsA = getCommitHistory(branchA);
        Map<String, CommitNode> commitsB = getCommitHistory(branchB);

        Set<String> commonCommits = new HashSet<>(commitsA.keySet());
        commonCommits.retainAll(commitsB.keySet());

        Map<String, CommitNode> commonCommitNodes = new HashMap<>();
        for (String shaKey : commonCommits) {
            commonCommitNodes.put(shaKey, commitCache.get(shaKey));
        }

        Set<String> lastCommonCommits = new HashSet<>(commonCommits);
        for (CommitNode node : commonCommitNodes.values()) {
            for (CommitNode parent : node.parents) {
                if (commonCommits.contains(parent.sha)) {
                    lastCommonCommits.remove(parent.sha);
                }
            }
        }

        return lastCommonCommits;
    }

    private Map<String, CommitNode> getCommitHistory(String branch) throws IOException {
        if (commitCache.containsKey(branch)) {
            return new HashMap<>(commitCache);
        }

        Map<String, CommitNode> commits = new HashMap<>();
        String cursor = null;
        int fetchedCommits = 0;

        while (fetchedCommits < MAX_COMMITS) {
            String query = buildGraphQLQuery(branch, cursor);
            JsonObject response = executeGraphQLQuery(query);

            JsonObject data = response.getAsJsonObject("data");
            if (data == null || data.isJsonNull()) {
                throw new IOException("No data in response");
            }

            JsonObject repository = data.getAsJsonObject("repository");
            if (repository == null || repository.isJsonNull()) {
                throw new IOException("Repository not found");
            }

            JsonObject ref = repository.getAsJsonObject("ref");
            if (ref == null || ref.isJsonNull()) {
                throw new IOException("Branch not found: " + branch);
            }

            JsonObject target = ref.getAsJsonObject("target");
            if (target == null || target.isJsonNull()) {
                throw new IOException("No target in ref");
            }

            JsonObject history = target.getAsJsonObject("history");
            if (history == null || history.isJsonNull()) {
                throw new IOException("No history in target");
            }

            JsonArray edges = history.getAsJsonArray("edges");
            if (edges == null || edges.isEmpty()) {
                break;
            }

            for (int i = 0; i < edges.size(); i++) {
                JsonObject edge = edges.get(i).getAsJsonObject();
                JsonObject node = edge.getAsJsonObject("node");
                String sha = node.get("oid").getAsString();

                List<CommitNode> parents = new ArrayList<>();
                if (node.has("parents") && !node.get("parents").isJsonNull()) {
                    JsonObject parentsObject = node.getAsJsonObject("parents");
                    if (parentsObject.has("edges") && !parentsObject.get("edges").isJsonNull()) {
                        JsonArray parentsEdgesArray = parentsObject.getAsJsonArray("edges");

                        for (int j = 0; j < parentsEdgesArray.size(); j++) {
                            JsonObject parentEdge = parentsEdgesArray.get(j).getAsJsonObject();
                            JsonObject parentNode = parentEdge.getAsJsonObject("node");
                            String parentSha = parentNode.get("oid").getAsString();
                            parents.add(new CommitNode(parentSha));
                        }
                    }
                }

                CommitNode commitNode = new CommitNode(sha);
                commitNode.parents = parents;
                commits.put(sha, commitNode);
                commitCache.put(sha, commitNode);
                fetchedCommits++;
            }

            JsonObject pageInfo = history.getAsJsonObject("pageInfo");
            boolean hasNextPage = pageInfo.get("hasNextPage").getAsBoolean();
            if (!hasNextPage) {
                break;
            }
            cursor = pageInfo.get("endCursor").getAsString();
        }

        return commits;
    }

//    private String buildGraphQLQuery(String branch, String cursor) {
//        return String.format("{\n" +
//                "  repository(owner: \"%s\", name: \"%s\") {\n" +
//                "    name\n" +
//                "    description\n" +
//                "  }\n" +
//                "}", owner, repo);
//    }


    private String buildGraphQLQuery(String branch, String cursor) {
        String afterCursor = cursor != null ? ", after: \"" + cursor + "\"" : "";
        return String.format("query {\n" +
                "  repository(owner: \"%s\", name: \"%s\") {\n" +
                "    ref(qualifiedName: \"%s\") {\n" +
                "      target {\n" +
                "        ... on Commit {\n" +
                "          history(first: 100%s) {\n" +
                "            pageInfo {\n" +
                "              hasNextPage\n" +
                "              endCursor\n" +
                "            }\n" +
                "            edges {\n" +
                "              node {\n" +
                "                oid\n" +
                "                parents(first: 10) {\n" +
                "                  edges {\n" +
                "                    node {\n" +
                "                      oid\n" +
                "                    }\n" +
                "                  }\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}", owner, repo, branch, afterCursor);
    }


    private JsonObject executeGraphQLQuery(String query) throws IOException {
        JsonObject jsonQuery = new JsonObject();
        jsonQuery.addProperty("query", query);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_GRAPHQL_API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonQuery.toString()));

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = requestBuilder.build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                return gson.fromJson(response.body(), JsonObject.class);
            } else {
                throw new IOException("Error code: " + statusCode + ". Response: " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }


    // Map json to this class (gson library)
    private static class CommitNode {
        String sha;
        List<CommitNode> parents;

        CommitNode(String sha) {
            this.sha = sha;
            this.parents = new ArrayList<>();
        }
    }
}
