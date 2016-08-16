package org.camunda.tngp.hashindex;

import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;

public interface IndexKeyHandler
{
    void setKeyLength(int keyLength);

    int keyHashCode();

    void readKey(MutableDirectBuffer buffer, int recordKeyOffset);

    void writeKey(MutableDirectBuffer buffer, int recordKeyOffset);

    boolean keyEquals(DirectBuffer buffer, int offset);

}
