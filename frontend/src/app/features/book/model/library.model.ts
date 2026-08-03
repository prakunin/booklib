import {SortOption} from './sort.model';
import {BookType} from './book.model';
import {IconType} from '../../../shared/icons/icon-selection';

export type MetadataSource = 'EMBEDDED' | 'SIDECAR' | 'PREFER_SIDECAR' | 'PREFER_EMBEDDED' | 'NONE';

export type OrganizationMode = 'BOOK_PER_FILE' | 'BOOK_PER_FOLDER' | 'AUTO_DETECT';
export type LibrarySourceType = 'FILESYSTEM' | 'INPX';

export interface Library {
  id?: number;
  name: string;
  icon?: string | null;
  iconType?: IconType | null;
  watch: boolean;
  sourceType?: LibrarySourceType;
  inpxPath?: string | null;
  inpxArchivePath?: string | null;
  /**
   * Directory of a local metadata catalog shipping alongside the library — for INPX libraries the
   * sibling "*.FLibrary.etc" folder holding annotations, reviews and author biographies.
   */
  metadataSidecarPath?: string | null;
  fileNamingPattern?: string;
  sort?: SortOption;
  paths: LibraryPath[];
  formatPriority?: BookType[];
  allowedFormats?: BookType[];
  metadataSource?: MetadataSource;
  organizationMode?: OrganizationMode;
}

export interface LibraryPath {
  id?: number;
  path: string;
}
