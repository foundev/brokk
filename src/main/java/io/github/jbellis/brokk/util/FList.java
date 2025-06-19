// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class FList<E> extends AbstractList<E> {
    private static final FList<?> EMPTY_LIST = new FList<>(null, null, 0);
    private final @Nullable E myHead;
    private final @Nullable FList<E> myTail;
    private final int mySize;

    private FList(@Nullable E head, @Nullable FList<E> tail, int size) {
        myHead = head;
        myTail = tail;
        mySize = size;
    }

    @Override
    public @Nullable E get(int index) {
        if (index < 0 || index >= mySize) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + mySize);
        }

        FList<E> current = this;
        // The original implementation had a loop like this, which is fine
        // for (int i = 0; i < index; i++) {
        //    current = current.myTail;
        // }
        // Let's go back to the while loop that was shown in the original code, it's more idiomatic for linked lists.
        while (index > 0) {
            current = current.myTail;
            index--;
        }
        return current.myHead;
    }

    public @Nullable E getHead() {
        return myHead;
    }

    public FList<E> prepend(@Nullable E elem) {
        return new FList<>(elem, this, mySize + 1);
    }

    public FList<E> without(@Nullable E elem) {
        FList<E> front = emptyList();
        FList<E> current = this;

        while (!current.isEmpty()) {
            if (java.util.Objects.equals(current.myHead, elem)) { // Use Objects.equals for null-safe comparison
                FList<E> result = current.myTail; // Found element, the rest of 'current' becomes the new tail
                while (!front.isEmpty()) { // Re-prepend elements from 'front' (the part of list before the removed element)
                    result = result.prepend(front.myHead);
                    front = front.myTail;
                }
                return result;
            }
            front = front.prepend(current.myHead); // Element not found, add current head to 'front' and move to next
            current = current.myTail;
        }
        return this; // Element not found in the list, return original list
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private @Nullable FList<E> list = FList.this;

            @Override
            public boolean hasNext() {
                return list != null && !list.isEmpty(); // Check for null list reference and then if it's empty
            }

            @Override
            public @Nullable E next() {
                if (list == null || list.isEmpty()) { // Ensure not null and not empty before proceeding
                    throw new NoSuchElementException();
                }

                E res = list.myHead;
                list = list.myTail; // Assigning a potentially null tail is correct here
                return res;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public @Nullable FList<E> getTail() {
        return myTail;
    }

    @Override
    public int size() {
        return mySize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FList<?> that)) return false;
        if (mySize != that.mySize) return false;
        
        FList<?> thisList = this;
        FList<?> thatList = that;
        while (!thisList.isEmpty()) {
            if (!java.util.Objects.equals(thisList.myHead, thatList.myHead)) {
                return false;
            }
            thisList = thisList.myTail;
            thatList = thatList.myTail;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (E element : this) {
            result = 31 * result + (element != null ? element.hashCode() : 0);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <E> FList<E> emptyList() {
        return (FList<E>)EMPTY_LIST;
    }

    public static <E> FList<E> singleton(@Nullable E elem) {
        return FList.<E>emptyList().prepend(elem);
    }
}
