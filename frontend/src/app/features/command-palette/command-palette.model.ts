import { IconSelection } from '../../shared/icons/icon-selection';

export type PaletteItemKind =
  | 'action'
  | 'page'
  | 'book'
  | 'semanticBook'
  | 'author'
  | 'series'
  | 'shelf'
  | 'magicShelf'
  | 'library';

export interface PaletteBookMeta {
  bookId: number;
  thumbnailUrl: string | null;
  authors: string[];
  seriesName: string | null;
  seriesNumber: number | null;
  year: string | null;
  isAudiobook: boolean;
}

export interface PaletteItem {
  id: string;
  kind: PaletteItemKind;
  title: string;
  subtitle?: string;
  icon?: IconSelection;
  searchText: string;
  route?: unknown[];
  queryParams?: Record<string, string>;
  command?: () => void;
  bookMeta?: PaletteBookMeta;
}

export interface PaletteGroup {
  kind: PaletteItemKind;
  items: PaletteItem[];
}
