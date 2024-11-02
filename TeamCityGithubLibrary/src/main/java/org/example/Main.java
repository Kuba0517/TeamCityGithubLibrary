package org.example;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {

        final String OWNER = ""; // provide the owner of the repository name
        final String REPOSITORY = ""; // provide the name of the repo
        final String TOKEN =  ""; // provide your token https://github.com/settings/tokens

        LastCommonCommitsFinder lastCommonCommitsFinder = new LastCommonCommitsFinderImpl(
                OWNER,
                REPOSITORY,
                TOKEN
        );

        try {
            System.out.println(lastCommonCommitsFinder.findLastCommonCommits("main", "frontend"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
