package org.alfasoftware.morf.xml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Tranforms version 2 XML format into version 3.
 * This essentially involves replacing (illegal) &#0; character references with \0 - the v3 representation of a null character.
 */
class Version2to3TranformingReader extends Reader {

  private final BufferedReader sourceReader;
  private char[] temporary = new char[] {};
  private int charsToSkip;
  private static final char[] nullRefChars = "&#0;".toCharArray();


  public Version2to3TranformingReader(BufferedReader bufferedReader) {
    super();
    this.sourceReader = bufferedReader;

    if (!sourceReader.markSupported()) {
      throw new UnsupportedOperationException("Mark support is required");
    }
  }


  static boolean shouldApplyTransform(BufferedReader bufferedReader) {
    try {
      bufferedReader.mark(100);
      try {
        {
          String line = bufferedReader.readLine();
          // the first line is probably the xml declaration
          boolean isXmlDeclaration = line.startsWith("<?xml") && line.endsWith("?>");
          if (!isXmlDeclaration) {
            return false;
          }
        }
        {
          String line = bufferedReader.readLine();
          // the next line is probably the table element
          boolean isTableElement = line.startsWith("<table version=\"");
          if (!isTableElement) {
            return false;
          }

          // apply the transform if the version number is 2
          return line.contains("version=\"2\"");
        }

      } finally {
        bufferedReader.reset();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    // We need to transform &#0; into \0...
    int charsRead;

    // skip some characters if we need to
    while (charsToSkip > 0) {
      int charsSkipped = sourceReader.read(new char[charsToSkip], 0, charsToSkip);
      if (charsSkipped < 0) {
        return 0;
      }
      charsToSkip -= charsSkipped;
    }

    // if there's no temporary buffer from a previous call, read from the main source
    if (temporary.length == 0) {
      // This is the common path
      charsRead = sourceReader.read(cbuf, off, len);
    } else {
      // there is a temporary buffer from a previous match, use that
      if (temporary.length > len) {
        // The temporary buffer is too big to fit in the buffer that's been supplied. This is an edge case, but we need to deal with it.
        // Copy out what we can, then create another temporary buffer for the remainder
        System.arraycopy(temporary, 0, cbuf, off, len);
        charsRead = len;
        char[] newTemporary = new char[temporary.length-len];
        System.arraycopy(temporary, len, newTemporary, 0, temporary.length-len);
        temporary = newTemporary;
      } else {
        // copy the entire temporary buffer into the output
        System.arraycopy(temporary, 0, cbuf, off, temporary.length);
        charsRead = temporary.length;
        temporary = new char[] {};
      }
    }

    // now search for the string we're replacing
    for (int idx=0; idx<charsRead; idx++) {
      if (cbuf[off+idx] == nullRefChars[0]) {
        // The first char matches.
        // Check whether the subsequent chars make up the ref
        if (isNullCharacterReference(cbuf, off+idx, charsRead-idx)) {
          // we have a match
          int charsRemainingInBuffer = charsRead-idx-nullRefChars.length;
          if (charsRemainingInBuffer < 0) {
            // can be less than zero if we read past the end of this buffer and into the next
            // in this case we need to skip some characters on the next read
            charsToSkip = -charsRemainingInBuffer;
            charsRemainingInBuffer = 0;
          }

          // Create a temporary buffer to hold the remainder of the buffer we haven't yet scanned
          // There might be an existing temporary buffer, in which case keep that too.
          char[] newTemporary = new char[2+charsRemainingInBuffer+temporary.length];

          // write the escaped null
          newTemporary[0] = '\\';
          newTemporary[1] = '0';

          // copy in what's left
          System.arraycopy(cbuf, off+idx+nullRefChars.length, newTemporary, 2, charsRemainingInBuffer);

          // keep any existing buffer
          System.arraycopy(temporary, 0, newTemporary, 2+charsRemainingInBuffer, temporary.length);

          temporary = newTemporary;

          // truncate the returned output to where we've got to
          return idx;
        }
      }
    }

    // If we got here we found no matches to replace, so we can just return the buffer as read.
    // This is the common path
    return charsRead;
  }


  /**
   * Tests whether a given index in the buffer is a full null character reference.
   * Reads forward if required, but resets the position.
   */
  private boolean isNullCharacterReference(char[] cbuf, int ampersandIndex, int remaining) throws IOException {
    char[] bufferToTest;
    int indexToTest;

    int additionalCharsRequired = nullRefChars.length-remaining;

    if (additionalCharsRequired > 0) {
      bufferToTest = new char[nullRefChars.length];
      // we need to read ahead because we don't have enough chars
      // first copy the remaining chars in
      System.arraycopy(cbuf, ampersandIndex, bufferToTest, 0, remaining);

      // copy in the remainder, resetting the reader after we've read it
      sourceReader.mark(nullRefChars.length);
      int writeIdx = remaining;
      while (writeIdx < nullRefChars.length) {
        int additionalCharsRead = sourceReader.read(bufferToTest, writeIdx, nullRefChars.length-writeIdx);
        if (additionalCharsRead < 0) {
          // end of stream
          sourceReader.reset();
          return false;
        }
        writeIdx += additionalCharsRead;
      }

      sourceReader.reset();

      indexToTest = 0;
    } else {
      // The common path - we have enough buffer to work with
      bufferToTest = cbuf;
      indexToTest = ampersandIndex;
    }

    // now test
    for (int i=0; i<nullRefChars.length; i++) {
      if (bufferToTest[indexToTest+i] != nullRefChars[i]) {
        return false;
      }
    }

    // if we get here, it matches
    return true;
  }

  @Override
  public void close() throws IOException {
    sourceReader.close();
  }
}