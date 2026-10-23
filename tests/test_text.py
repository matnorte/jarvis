from server.text import ClauseChunker, clean_for_speech


def test_clean_strips_markdown_and_urls():
    assert clean_for_speech("**Done**, sir. See https://github.com/nous/x\n- item") == "Done, sir. See github.com item"


def test_clean_headings_fences_inline_code_links():
    text = "# Title\n```python\nprint(1)\n```\nUse `ls` and [docs](https://www.example.com/a?b=1)."
    assert clean_for_speech(text) == "Title print(1) Use ls and docs."


def test_clean_url_trailing_punctuation_and_emphasis():
    assert clean_for_speech("Visit https://example.com/path. It is *great* and __bold__!") == (
        "Visit example.com. It is great and bold!"
    )


def test_clean_keeps_snake_case():
    assert clean_for_speech("set my_var_name now") == "set my_var_name now"


def test_chunks_are_whole_sentences():
    c = ClauseChunker()
    out = c.feed("Certainly sir, the weather ") + c.feed("today is mild. Anything else?")
    assert out == ["Certainly sir, the weather today is mild."]
    assert c.flush() == ["Anything else?"]


def test_comma_does_not_break_a_normal_sentence():
    c = ClauseChunker()
    assert c.feed("Yes, ") == []
    assert c.feed("certainly sir, ") == []
    assert c.feed("one, two, three. ") == ["Yes, certainly sir, one, two, three."]


def test_long_sentence_breaks_at_clause_after_100_chars():
    c = ClauseChunker()
    head = "I have reviewed the quarterly figures you asked for this morning and compared them with last year carefully"
    assert len(head) >= 100
    assert c.feed(head + ", and ") == [head + ","]
    assert c.feed("they look good. ") == ["and they look good."]


def test_punctuation_at_buffer_end_waits():
    c = ClauseChunker()
    assert c.feed("The value is 3.") == []
    assert c.feed("5 today, sir. Ok") == ["The value is 3.5 today, sir."]
    assert c.feed(" fine.") == []
    assert c.flush() == ["Ok fine."]


def test_decimal_and_trailing_abbreviation_not_split():
    c = ClauseChunker()
    assert c.feed("It is about 3.5 degrees warmer in the U.S.") == []
    assert c.flush() == ["It is about 3.5 degrees warmer in the U.S."]


def test_flush_returns_remainder_and_never_empty():
    c = ClauseChunker(); c.feed("Hello there"); assert c.flush() == ["Hello there"]
    assert ClauseChunker().flush() == []


def test_chunk_that_cleans_to_empty_is_dropped():
    c = ClauseChunker()
    assert c.feed("``` \n") == []
    assert c.flush() == []


def test_chunks_are_cleaned():
    c = ClauseChunker()
    assert c.feed("**Certainly** sir. See https://github.com/x ") == ["Certainly sir."]
    assert c.flush() == ["See github.com"]


def _all_chunks(c, *deltas):
    out = []
    for d in deltas:
        out += c.feed(d)
    return out + c.flush()


def _no_delims(chunks):
    return all(not any(t in ch for t in ("*", "`", "[", "](")) for ch in chunks)


def test_emphasis_spanning_boundary_is_clean():
    chunks = _all_chunks(ClauseChunker(), "**Important, sir. Please note** this.")
    assert _no_delims(chunks)
    assert " ".join(chunks) == "Important, sir. Please note this."


def test_inline_code_spanning_boundary_is_clean():
    chunks = _all_chunks(ClauseChunker(), "Run `a, b` now, sir. Then `c, d", "` ok.")
    assert _no_delims(chunks)
    assert " ".join(chunks) == "Run a, b now, sir. Then c, d ok."


def test_link_spanning_boundary_is_clean():
    chunks = _all_chunks(ClauseChunker(), "See the [docs, here](https://x.com/a) ok, sir. Bye.")
    assert _no_delims(chunks)
    assert " ".join(chunks) == "See the docs, here ok, sir. Bye."


def test_unclosed_delimiter_flushes_without_symbols():
    assert _no_delims(_all_chunks(ClauseChunker(), "**never closed, sir. more"))


def test_first_chunk_may_break_at_clause_after_40_chars():
    c = ClauseChunker()
    head = "Certainly, sir, I have checked the calendar"
    assert len(head) >= 40
    assert c.feed(head + ", and ") == [head + ","]
    # later chunks need 100 characters before a clause break
    assert c.feed("you are free all afternoon, sir. ") == ["and you are free all afternoon, sir."]


def test_short_first_clause_still_waits_for_sentence():
    c = ClauseChunker()
    assert c.feed("Certainly, sir, ") == []
