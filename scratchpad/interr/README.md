# The subject-grounding measurement

Compiles the SHIPPED `GuideSearch` + `LibraryConsult` and runs the real `consult` gate
(`distinctiveToken` -> `rank` -> `isTopical`) over the real 651-guide index, against realistic
SPOKEN claims rather than typed questions.

    bash scratchpad/interr/run.sh

This is what established that grounding the interrogator on the subject of an utterance does not
work with the current bar: it keys on the globally rarest word, which in speech is usually filler
(*minutes*, *either*, *obviously*, *grandfather*), so it cites the wrong guide twice and refuses the
right one four times — including pages it had already ranked first. Re-run it against any proposed
new bar before believing the bar is better.

`index.tsv` is regenerated from `app/src/main/assets/survival/guide_index.json`; see the one-liner
in the session log, or just re-export id/title/category/summary/headings tab-separated.
