package io.github.jbellis.brokk.difftool.search;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class SearchHits {
    private final List<SearchHit> searchHits;
    @Nullable
    private SearchHit current;

    public SearchHits() {
        searchHits = new ArrayList<>();
        current = null;
    }

    public void add(SearchHit sh) {
        searchHits.add(sh);
        if (getCurrent() == null) {
            setCurrent(sh);
        }
    }

    public List<SearchHit> getSearchHits() {
        return searchHits;
    }

    public boolean isCurrent(SearchHit sh) {
        var currentHit = getCurrent();
        return currentHit != null && sh.equals(currentHit);
    }

    @Nullable
    public SearchHit getCurrent() {
        return current;
    }

    private void setCurrent(SearchHit sh) {
        current = sh;
    }

    public void next() {
        if (searchHits.isEmpty() || getCurrent() == null) {
            return;
        }
        
        int index = searchHits.indexOf(getCurrent());
        setCurrent(searchHits.get((index + 1) % searchHits.size()));
    }

    public void previous() {
        if (searchHits.isEmpty() || getCurrent() == null) {
            return;
        }
        
        int index = searchHits.indexOf(getCurrent());
        setCurrent(searchHits.get((index - 1 + searchHits.size()) % searchHits.size()));
    }
}
