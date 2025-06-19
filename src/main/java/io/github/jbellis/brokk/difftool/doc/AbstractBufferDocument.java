package io.github.jbellis.brokk.difftool.doc;

import org.jetbrains.annotations.Nullable;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.text.BadLocationException;
import javax.swing.text.GapContent;
import javax.swing.text.PlainDocument;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.Objects;
import java.util.stream.Collectors;

public abstract class AbstractBufferDocument implements BufferDocumentIF, DocumentListener {
    private String name = "";
    private String shortName = "";
    // These fields are initialized in initializeAndRead and are non-null afterwards
    private Line[] lineArray;
    private int[] lineOffsetArray;
    private PlainDocument document;
    private MyGapContent content;
    private final List<BufferDocumentChangeListenerIF> listeners;

    private boolean changed;
    private int originalLength;
    private int digest;

    protected AbstractBufferDocument() {
        listeners = new ArrayList<>();
    }

    // Called by subclasses after setting name/shortName
    protected void initializeAndRead() {
        if (document != null) {
            document.removeDocumentListener(this);
        }
        content = new MyGapContent(getBufferSize() + 500);
        document = new PlainDocument(content);
        try {
            new DefaultEditorKit().read(getReader(), document, 0);
        } catch (Exception readEx) {
            throw new RuntimeException("Failed to read content for " + getName(), readEx);
        }
        document.addDocumentListener(this);
        resetLineCache();
        initLines(); // Initialize lines immediately after reading
        initDigest();
    }

    @Override
    public void addChangeListener(BufferDocumentChangeListenerIF listener) {
        listeners.add(listener);
    }

    @Override
    public void removeChangeListener(BufferDocumentChangeListenerIF listener) {
        listeners.remove(listener);
    }

    protected abstract int getBufferSize();

    @Override
    public abstract Reader getReader();

    public abstract Writer getWriter() throws Exception;

    protected void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    protected void setShortName(String shortName) {
        this.shortName = shortName;
    }

    @Override
    public String getShortName() {
        return shortName.isEmpty() ? name : shortName;
    }

    @Override
    public PlainDocument getDocument() {
        // Document is initialized by initializeAndRead() before any calls to this
        assert document != null : "Document accessed before initialization for " + getName();
        return document;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public Line[] getLines() {
        initLines();
        return lineArray;
    }

    @Override
    public int getOffsetForLine(int lineNumber) {
        if (lineNumber < 0) {
            return -1;
        }
        Line[] la = getLines(); // Ensures lines are initialized and la is non-null.
        if (la.length == 0) { // Handle empty document
            return (lineNumber == 0) ? 0 : -1; // Offset 0 for line 0 in empty doc
        }
        if (lineNumber == 0) {
            return 0;
        }
        // If requesting offset for line *after* the last line, return document length
        if (lineNumber > la.length) {
            return getDocument().getLength();
        }
        // Otherwise, return the start offset of the requested line (end offset of previous)
        return la[lineNumber - 1].getEndOffset(); // Use end offset of previous line
    }

    @Override
    public int getLineForOffset(int offset) {
        if (offset < 0) {
            return 0; 
        }
        initLines(); // Ensures lines are initialized and document exists
        if (lineOffsetArray.length == 0) {
            return 0;
        }

        if (offset >= document.getLength()) {
            return Math.max(0, lineArray.length - 1);
        }

        int searchIndex = Arrays.binarySearch(lineOffsetArray, offset);

        if (searchIndex >= 0) {
            return searchIndex;
        } else {
            int insertionPoint = -searchIndex - 1;
            return Math.max(0, insertionPoint - 1);
        }
    }

    private void initLines() {
        if (lineArray != null) {
            return;
        }
        // document and content must be non-null here due to initializeAndRead() contract
        assert document != null;
        Element paragraph = document.getDefaultRootElement();
        int size = paragraph.getElementCount();
        lineArray = new Line[size];
        lineOffsetArray = new int[lineArray.length];
        for (int i = 0; i < lineArray.length; i++) {
            Element e = paragraph.getElement(i);
            Line line = new Line(e);
            lineArray[i] = line;
            // Store the *start* offset for binary search in getLineForOffset
            lineOffsetArray[i] = line.getStartOffset();
        }
    }

    protected void resetLineCache() {
        lineArray = null;
        lineOffsetArray = null;
    }

    @Override
    public void write() {
        // Ensure document exists before writing
        if (document == null) {
            throw new IllegalStateException("Cannot write document, it was not initialized: " + getName());
        }
        try (Writer out = getWriter()) {
            if (out == null) {
                throw new RuntimeException("Cannot get writer for document: " + getName());
            }
            new DefaultEditorKit().write(out, document, 0, document.getLength());
            out.flush();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to write document: " + getName(), ex);
        }
        initDigest(); // Update digest after successful write
    }

    class MyGapContent extends GapContent {
        public MyGapContent(int length) {
            super(length);
        }

        char[] getCharArray() {
            return (char[]) getArray();
        }

        public char charAtOffset(int offset) {
            return charAt(getCharArray(), offset, getGapStart(), getGapEnd());
        }

        public boolean equals(@Nullable MyGapContent c2, int start1, int end1, int start2) {
            if (c2 == null) return false; // Handle null c2
            char[] array1 = getCharArray();
            char[] array2 = c2.getCharArray();
            int g1_0 = getGapStart();
            int g1_1 = getGapEnd();
            int g2_0 = c2.getGapStart();
            int g2_1 = c2.getGapEnd();

            int len1 = end1 - start1;
            int current1 = start1;
            int current2 = start2;

            for (int i = 0; i < len1; i++) {
                char c1 = charAt(array1, current1++, g1_0, g1_1);
                char charVal2 = charAt(array2, current2++, g2_0, g2_1); // Renamed local variable
                if (c1 != charVal2) {
                    return false;
                }
            }
            return true;
        }

        private char charAt(char[] array, int index, int gapStart, int gapEnd) {
            if (index < gapStart) {
                return array[index];
            } else {
                return array[index + (gapEnd - gapStart)];
            }
        }

        public int hashCode(int start, int end) {
            char[] array = getCharArray();
            int g0 = getGapStart();
            int g1 = getGapEnd();
            int h = 0;
            int current = start;
            int len = end - start;

            for (int i = 0; i < len; i++) {
                char c = charAt(array, current++, g0, g1);
                h = 31 * h + c;
            }

            if (h == 0 && len > 0) { // Avoid hash 0 for non-empty strings if possible
                h = 1;
            }
            return h;
        }

        public int getDigest() {
            return hashCode(0, AbstractBufferDocument.this.document.getLength());
        }
    }

    public class Line implements Comparable<Line> {
        final Element element;

        Line(Element element) {
            this.element = element;
        }

        MyGapContent getContent() {
            // Content should be initialized by initializeAndRead()
            assert content != null : "Line accessed before content was initialized";
            return content;
        }

        public int getStartOffset() {
            return element.getStartOffset();
        }

        public int getEndOffset() {
            return element.getEndOffset();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Line otherLine)) {
                return false;
            }
            Element element2 = otherLine.element;
            int start1 = this.getStartOffset();
            int end1 = this.getEndOffset();
            int start2 = element2.getStartOffset();
            int end2 = element2.getEndOffset();

            if ((end1 - start1) != (end2 - start2)) {
                return false;
            }
            // getContent() is asserted non-null
            return this.getContent().equals(otherLine.getContent(), start1, end1, start2);
        }

        @Override
        public int hashCode() {
            // getContent() is asserted non-null
            return this.getContent().hashCode(getStartOffset(), getEndOffset());
        }

        /**
         * Returns the text for this line. If {@code getEndOffset()} goes beyond the
         * document length (common for the last line with trailing newline),
         * we clamp the end offset to the doc length before substring retrieval.
         */
        @Override
        public String toString() {
            try {
                int start = getStartOffset();
                int end = getEndOffset();
                int docLen = document.getLength(); // document is asserted non-null

                if (start < 0 || end < 0 || start > docLen) {
                    return "<INVALID RANGE>";
                }

                // If end is docLen+1, that usually indicates a final trailing newline in PlainDocument
                if (end > docLen) {
                    if (end == docLen + 1) {
                        // We'll clamp substring to docLen, then manually re-append "\n" if needed
                        int length = Math.max(0, docLen - start);
                        if (length == 0) {
                            // Possibly just the very last trailing newline
                            if (docLen > 0) {
                                char lastChar = getContent().charAtOffset(docLen - 1);
                                if (lastChar == '\n') {
                                    return "\n";
                                }
                            }
                            return "";
                        }
                        // Otherwise substring from [start..docLen], then if last char is newline, append it
                        String text = getContent().getString(start, length);
                        if (docLen > 0) {
                            char lastChar = getContent().charAtOffset(docLen - 1);
                            if (lastChar == '\n') {
                                text += "\n";
                            }
                        }
                        return text;
                    } else {
                        // More than one past docLen => genuinely invalid
                        return "<INVALID RANGE>";
                    }
                }

                // Normal case: offsets are within [0..docLen]
                int length = end - start;
                if (length <= 0) {
                    return "";
                }
                return getContent().getString(start, length);

            } catch (BadLocationException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public int compareTo(Line line) {
            return toString().compareTo(line.toString());
        }
    }

    @Override
    public void changedUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    @Override
    public void insertUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    @Override
    public void removeUpdate(DocumentEvent de) {
        documentChanged(de);
    }

    private void initDigest() {
        originalLength = document.getLength();
        digest = content.getDigest();
        changed = false;
    }

    private void documentChanged(DocumentEvent de) {
        JMDocumentEvent jmde = new JMDocumentEvent(this, de);

        resetLineCache();
        int offset = de.getOffset();
        int startLine = getLineForOffset(offset);
        jmde.setStartLine(startLine);

        boolean newChanged;
        if (document.getLength() != originalLength) {
            newChanged = true;
        } else {
            int newDigest = content.getDigest();
            newChanged = (newDigest != digest);
        }

        if (newChanged || changed) {
            changed = true;
            fireDocumentChanged(jmde);
        }
    }

    private void fireDocumentChanged(JMDocumentEvent de) {
        List<BufferDocumentChangeListenerIF> listenersCopy = new ArrayList<>(listeners);
        for (BufferDocumentChangeListenerIF listener : listenersCopy) {
            listener.documentChanged(de);
        }
    }

    @Override
    public boolean isReadonly() {
        return true;
    }

    @Override
    public String getLineText(int lineNumber) {
        Line[] la = getLines();
        if (lineNumber < 0 || lineNumber >= la.length) {
            System.err.println("getLineText: Invalid line number " + lineNumber + " for document " + getName());
            return "<INVALID LINE>";
        }
        return la[lineNumber].toString();
    }

    @Override
    public int getNumberOfLines() {
        return getLines().length;
    }

    /**
     * Returns the entire document as a list of strings, one per line.
     * This is used directly by the diff logic.
     */
    @Override
    public List<String> getLineList() {
        initLines();
        return Arrays.stream(lineArray)
                    .map(Line::toString)
                    .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[name=" + name + "]";
    }
}
