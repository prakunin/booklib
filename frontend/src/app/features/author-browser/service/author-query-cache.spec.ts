import {InfiniteData, QueryClient} from '@tanstack/angular-query-experimental';
import {describe, expect, it} from 'vitest';

import {AppPageResponse} from '../../book/model/app-book.model';
import {AuthorSummary} from '../model/author.model';
import {AUTHORS_QUERY_KEY} from './author-query-keys';
import {patchAuthorInCache, removeAuthorsFromCache} from './author-query-cache';

function page(authors: AuthorSummary[]): InfiniteData<AppPageResponse<AuthorSummary>> {
  return {
    pages: [{
      content: authors,
      page: 0,
      size: 50,
      totalElements: authors.length,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    }],
    pageParams: [0],
  };
}

describe('patchAuthorInCache', () => {
  it('patches the matching author while keeping the rest intact', () => {
    const queryClient = new QueryClient();
    const queryKey = [...AUTHORS_QUERY_KEY, '', {}, 'name', 'asc'];
    queryClient.setQueryData(queryKey, page([
      {id: 1, name: 'Ada', bookCount: 1, hasPhoto: false},
      {id: 2, name: 'Bert', bookCount: 2, hasPhoto: true},
    ]));

    patchAuthorInCache(queryClient, 2, {name: 'Bert Updated', hasPhoto: false});

    expect(queryClient.getQueryData<InfiniteData<AppPageResponse<AuthorSummary>>>(queryKey)?.pages[0].content).toEqual([
      {id: 1, name: 'Ada', bookCount: 1, hasPhoto: false},
      {id: 2, name: 'Bert Updated', bookCount: 2, hasPhoto: false},
    ]);
  });

  it('leaves the cache absent when there is no existing author page', () => {
    const queryClient = new QueryClient();

    patchAuthorInCache(queryClient, 3, {name: 'Cy'});

    expect(queryClient.getQueryData(AUTHORS_QUERY_KEY)).toBeUndefined();
  });

  it('removes authors from every loaded page and updates totals', () => {
    const queryClient = new QueryClient();
    const queryKey = [...AUTHORS_QUERY_KEY, '', {}, 'name', 'asc'];
    const cached = page([{id: 1, name: 'Ada', bookCount: 1, hasPhoto: false}]);
    cached.pages.push({
      ...cached.pages[0],
      page: 1,
      content: [{id: 2, name: 'Bert', bookCount: 2, hasPhoto: true}],
    });
    cached.pages.forEach(currentPage => {
      currentPage.totalElements = 2;
      currentPage.totalPages = 2;
      currentPage.hasNext = currentPage.page === 0;
    });
    cached.pageParams.push(1);
    queryClient.setQueryData(queryKey, cached);

    removeAuthorsFromCache(queryClient, [2]);

    const result = queryClient.getQueryData<InfiniteData<AppPageResponse<AuthorSummary>>>(queryKey);
    expect(result?.pages[0].content.map(author => author.id)).toEqual([1]);
    expect(result?.pages[1].content).toEqual([]);
    expect(result?.pages[0].totalElements).toBe(1);
    expect(result?.pages[1].totalElements).toBe(1);
  });
});
