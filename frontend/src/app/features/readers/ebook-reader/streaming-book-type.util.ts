import type {BookType} from '../../book/model/book.model';

export function shouldUseStreamingRendition(bookType: BookType, epubStreamingEnabled: boolean): boolean {
  return bookType === 'DOC' || bookType === 'HTML' || (bookType === 'EPUB' && epubStreamingEnabled);
}
