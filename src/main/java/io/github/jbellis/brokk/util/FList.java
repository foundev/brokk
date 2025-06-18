// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.NoSuchElementException;
// import java.util.Objects; // Not strictly needed for the requested changes and existing Objects.equals usage.

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
        for (int i = 0; i < index; i++) {
            if (current.myTail == null) { // current is EMPTY_LIST or list structure is broken
                break; 
            }
            current = current.myTail;
        }
        return current.myHead;
    }

    public @Nullable E getHead() {
        return myHead;
    }

    // Elem can be @Nullable if the list is intended to store nulls.
    public FList<E> prepend(@Nullable E elem) {
        return new FList<>(elem, this, mySize + 1);
    }

    public FList<E> without(@Nullable E elem) {
        FList<E> front = emptyList();

        FList<E> current = this;
        while (!current.isEmpty()) {
            // current.myHead can be null if 'E' itself is @Nullable and a null was added.
            // If 'elem' is null, we are looking to remove a null.
            // If 'elem' is not null, 'current.myHead' must also not be null for .equals to be safe.
            if (elem == null) {
                if (current.myHead == null) { // Found a null to remove
                    return reverseAndConcat(front, current.myTail);
                }
            } else { // elem is not null
                if (current.myHead != null && current.myHead.equals(elem)) { // Found a non-null element to remove
                    return reverseAndConcat(front, current.myTail);
                }
            }

            // Element not found at current.myHead, move it to 'front' and continue.
            // Prepending a potentially null myHead is fine if E is @Nullable.
            front = front.prepend(current.myHead);
            current = current.myTail;
            if (current == null) break; // Should be caught by isEmpty if tail is properly null for EMPTY_LIST
        }
        return this; // Element not found
    }

    // Helper to reverse 'front' and prepend it to 'tail'
    private FList<E> reverseAndConcat(FList<E> front, @Nullable FList<E> tail) {
        FList<E> result = (tail == null) ? emptyList() : tail;
        while (!front.isEmpty()) {
            // front.myHead can be null if E is @Nullable and a null was added.
            // Prepending a potentially null head is fine if E is @Nullable.
            result = result.prepend(front.myHead);
            front = front.myTail;
            if (front == null) break; // Should be caught by isEmpty
        }
        return result;
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private @Nullable FList<E> list = FList.this;

            @Override
            public boolean hasNext() {
                return list != null && !list.isEmpty();
            }

            @Override
            public @Nullable E next() {
                if (list == null || list.isEmpty()) {
                    throw new NoSuchElementException();
                }

                E res = list.myHead; 
                list = list.myTail;  
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
        if (o instanceof FList) {
            FList<?> list1 = this;
                FList<?> list2 = (FList<?>)o;
                if (mySize != list2.mySize) return false;
                while (list1 != null && !list1.isEmpty() && list2 != null && !list2.isEmpty()) {
                    if (!java.util.Objects.equals(list1.myHead, list2.myHead)) return false;
                    list1 = list1.myTail;
                    list2 = list2.myTail;
                    // If both tails become null simultaneously, we've reached the end and all elements matched
                    if (list1 == null && list2 == null) return true;
                    // If sizes matched initially, tails should become null at the same time if elements are equal
                    // If one becomes null before the other, something is wrong (shouldn't happen if sizes match)
                    if (list1 == null || list2 == null) return false; // Should not happen if size check passed
            }
            return true;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        int result = 1;
        FList each = this;
        while (each != null) {
            result = result * 31 + (each.myHead != null ? each.myHead.hashCode() : 0);
            each = each.getTail();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <E> FList<E> emptyList() {
        //noinspection unchecked
        return (FList<E>)EMPTY_LIST;
    }

    public static <E> FList<E> singleton(@Nullable E elem) { // elem can be @Nullable
        return FList.<E>emptyList().prepend(elem);
    }
}
