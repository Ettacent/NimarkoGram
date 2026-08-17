"""
extera_utils.text_formatting — markdown / HTML helpers for plugins.

Re-exports NimarkoGram's markdown engine and adds an ``HTML`` parser, so a
plugin can turn formatted text into a ``ParsedMessage`` (text + RawEntity list)
ready to send. Each ``RawEntity`` exposes ``to_tlrpc_object()``.

    from extera_utils.text_formatting import Markdown, HTML
    parsed = Markdown.parse("**bold** and `code`")
    entities = [e.to_tlrpc_object() for e in parsed.entities]
"""

from html.parser import HTMLParser

from markdown_utils import (
    parse_markdown,
    RawEntity,
    TLEntityType,
    ParsedMessage,
)

__all__ = ['parse_markdown', 'RawEntity', 'TLEntityType', 'ParsedMessage',
           'Markdown', 'HTML']

def _utf16_len(text: str) -> int:
    n = 0
    for ch in text:
        n += 2 if ord(ch) > 0xFFFF else 1
    return n

class Markdown:
    """Thin namespace over NimarkoGram's markdown engine."""

    @staticmethod
    def parse(text: str) -> ParsedMessage:
        return parse_markdown(text or '')

_TAG_TO_TYPE = {
    'b': TLEntityType.BOLD, 'strong': TLEntityType.BOLD,
    'i': TLEntityType.ITALIC, 'em': TLEntityType.ITALIC,
    'u': TLEntityType.UNDERLINE, 'ins': TLEntityType.UNDERLINE,
    's': TLEntityType.STRIKETHROUGH, 'strike': TLEntityType.STRIKETHROUGH,
    'del': TLEntityType.STRIKETHROUGH,
    'code': TLEntityType.CODE, 'pre': TLEntityType.PRE,
}

class _HTMLToEntities(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text_parts = []
        self.offset = 0           
        self.entities = []
        self._open = []           

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        tag = tag.lower()
        if tag == 'br':
            self._emit('\n')
            return
        if tag == 'a':
            self._open.append((TLEntityType.TEXT_LINK, self.offset,
                               {'url': attrs.get('href')}))
        elif tag == 'span' and 'tg-spoiler' in (attrs.get('class') or ''):
            self._open.append((TLEntityType.SPOILER, self.offset, {}))
        elif tag in _TAG_TO_TYPE:
            extra = {}
            if tag == 'pre' and attrs.get('language'):
                extra['language'] = attrs.get('language')
            self._open.append((_TAG_TO_TYPE[tag], self.offset, extra))

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag == 'br':
            return
        for i in range(len(self._open) - 1, -1, -1):
            etype, start, extra = self._open[i]
            matches = (
                (tag == 'a' and etype == TLEntityType.TEXT_LINK) or
                (tag == 'span' and etype == TLEntityType.SPOILER) or
                (_TAG_TO_TYPE.get(tag) == etype)
            )
            if matches:
                length = self.offset - start
                if length > 0:
                    self.entities.append(RawEntity(
                        type=etype, offset=start, length=length,
                        url=extra.get('url'), language=extra.get('language')))
                del self._open[i]
                break

    def handle_data(self, data):
        self._emit(data)

    def _emit(self, data):
        self.text_parts.append(data)
        self.offset += _utf16_len(data)

class HTML:
    """Parse a subset of Telegram-flavoured HTML into a ParsedMessage."""

    @staticmethod
    def parse(text: str) -> ParsedMessage:
        parser = _HTMLToEntities()
        parser.feed(text or '')
        parser.close()
        entities = sorted(parser.entities, key=lambda e: (e.offset, e.length))
        return ParsedMessage(text=''.join(parser.text_parts),
                             entities=tuple(entities))
