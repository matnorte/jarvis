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


def test_first_chunk_breaks_early_at_comma():
    c = ClauseChunker()
    out = c.feed("Certainly sir, the weather ") + c.feed("today is mild. Anything else?")
    assert out == ["Certainly sir,", "the weather today is mild."]
    assert c.flush() == ["Anything else?"]


def test_short_comma_does_not_break():
    c = ClauseChunker()
    assert c.feed("Yes, ") == []


def test_short_comma_skipped_then_later_clause_breaks():
    c = ClauseChunker()
    assert c.feed("Yes, certainly sir, ") == ["Yes, certainly sir,"]


def test_after_first_chunk_only_sentence_ends():
    c = ClauseChunker()
    assert c.feed("Certainly sir, one, two, three. ") == ["Certainly sir,", "one, two, three."]


def test_punctuation_at_buffer_end_waits():
    c = ClauseChunker()
    assert c.feed("The value is 3.") == []
    assert c.feed("5 today, sir. Ok") == ["The value is 3.5 today,", "sir."]
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
    assert c.feed("**Certainly** sir, see https://github.com/x ") == ["Certainly sir,"]
    assert c.flush() == ["see github.com"]
