# The DOM decimator's real-page gate

`Readability` is checked two ways, and only one of them finds anything.

`ReadabilityTest` covers the rules. **This directory covers whether it works.** Every defect worth
having found was found here and not there — a byline that was a profile URL, a security-advisory
table that arrived as the article's opening paragraph with every cell run together, a grey
placeholder standing in for the photograph, and `descend()` stepping into a dominant `<p>` and
discarding the subheadings after it. Fixtures cannot find those, because the fixture is written by
whoever wrote the rule.

## Running it

    ./run.sh <pages-dir>                 # the verdict table
    ./run.sh <pages-dir> <SomeTest.kt>   # a JUnit class instead

`JSOUP=/path/to/jsoup.jar` overrides the jar; otherwise it is taken from the Gradle cache.

## Getting a corpus

Fetch with a real browser user agent, into one directory, and add the URL to `Probe.kt`'s `bases`
map (the base URL matters — it resolves relative images and is how a Google News link is recognised).
Keep the failure cases: an index page, a 401, a redirect stub and a 404 are the interesting half.

## What the last run said

14 of 15 correct. The one exception is LWN's article index, which is accepted as an article because
it genuinely is a page of prose blurbs — see the class KDoc, which records the measurement showing
no threshold separates it from a real article.
