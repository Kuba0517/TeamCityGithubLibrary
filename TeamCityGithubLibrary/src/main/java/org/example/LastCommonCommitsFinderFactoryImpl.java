package org.example;

public class LastCommonCommitsFinderFactoryImpl implements LastCommonCommitsFinderFactory {

    @Override
    public LastCommonCommitsFinder create(String owner, String repo, String token) {
        return new LastCommonCommitsFinderImpl(owner, repo, token);
    }
}

