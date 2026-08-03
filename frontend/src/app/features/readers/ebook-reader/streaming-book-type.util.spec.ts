import {describe, expect, it} from 'vitest';
import type {BookType} from '../../book/model/book.model';
import {shouldUseStreamingRendition} from './streaming-book-type.util';

describe('shouldUseStreamingRendition', () => {
  it.each<BookType>(['DOC', 'HTML'])('always streams %s through the server rendition', bookType => {
    expect(shouldUseStreamingRendition(bookType, true)).toBe(true);
    expect(shouldUseStreamingRendition(bookType, false)).toBe(true);
  });

  it('honors the streaming preference for EPUB', () => {
    expect(shouldUseStreamingRendition('EPUB', true)).toBe(true);
    expect(shouldUseStreamingRendition('EPUB', false)).toBe(false);
  });

  it.each<BookType>(['FB2', 'MOBI', 'AZW3'])('keeps %s on the native Foliate path', bookType => {
    expect(shouldUseStreamingRendition(bookType, true)).toBe(false);
    expect(shouldUseStreamingRendition(bookType, false)).toBe(false);
  });
});
