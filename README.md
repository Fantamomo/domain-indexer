# domain-indexer

This program checks the [Hackclub/dns](https://github.com/HackClub/dns) repo and all of its forks.

**Yes, all its forks.**

It clones those repos and stores them in a local git repo.
Then it goes through each commit and indexes those files. It extracts all the domain records from those files, and stores them in a postgres database.

It does that all 5 minutes (of course optimized so that there are no rate limits).

If it finds a new domain or updated one, it sends a slack message, to the [#dns-tracker](https://hackclub.enterprise.slack.com/archives/C0B6EV1FYKU) channel.

There are still some bugs, because of the complexity of git and vsc (currently deleted records are broken).