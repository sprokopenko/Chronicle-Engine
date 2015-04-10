/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.engine.client.ClientWiredStatelessTcpConnectionHub.CoreFields;
import net.openhft.chronicle.network.WireHandler;
import net.openhft.chronicle.network.WireTcpHandler;
import net.openhft.chronicle.network.event.WireHandlers;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;

import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.List;


/**
 * Created by Rob Austin
 */
public class EngineWireHandler extends WireTcpHandler implements WireHandlers {


    public static final String TEXT_WIRE = TextWire.class.getSimpleName();
    public static final String BINARY_WIRE = BinaryWire.class.getSimpleName();
    public static final String RAW_WIRE = RawWire.class.getSimpleName();

    private final CharSequence preferredWireType = new StringBuilder(TextWire.class.getSimpleName());
    private final StringBuilder cspText = new StringBuilder();

    @NotNull
    private final WireHandler mapWireHandler;

    @NotNull
    private final WireHandler queueWireHandler;

    @NotNull
    private final WireHandler coreWireHandler = new CoreWireHandler();

    public EngineWireHandler(@NotNull final WireHandler mapWireHandler,
                             final WireHandler queueWireHandler) {
        this.mapWireHandler = mapWireHandler;
        this.queueWireHandler = queueWireHandler;
    }

    private final List<WireHandler> handlers = new ArrayList<>();

    protected void publish(Wire out) {
        if (!handlers.isEmpty()) {

            final WireHandler remove = handlers.remove(handlers.size() - 1);

            try {
                remove.process(null, out);
            } catch (StreamCorruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected void process(Wire in, Wire out) throws StreamCorruptedException {


        final StringBuilder cspText = peekCsp(in);

        if (endsWith(cspText, "#MAP")) {
            mapWireHandler.process(in, out);
            return;
        }

        if (endsWith(cspText, "QUEUE")) {
            queueWireHandler.process(in, out);
            return;
        }

        if (endsWith(cspText, "CORE"))
            coreWireHandler.process(in, out);
    }

    private StringBuilder peekCsp(@NotNull final Wire in) {

        final Bytes<?> bytes = in.bytes();

        try {
            System.out.println("--------------------------------------------\nserver reads:\n\n" +
                    Wires.fromSizePrefixedBlobs(in.bytes()));
        } catch (Exception e) {
            System.out.println("--------------------------------------------\nserver reads:\n\n" +
                    Bytes.toDebugString(in.bytes()));
        }

        try {
            bytes.mark();
            inWire.readDocument(wireIn -> wireIn.read(CoreFields.csp).text(cspText), null);
        } finally {
            bytes.reset();
        }

        return cspText;
    }

    private boolean endsWith(@NotNull final CharSequence source, @NotNull final String endsWith) {

        for (int i = 1; i <= endsWith.length(); i++) {

            if (source.charAt(source.length() - i) != endsWith.charAt(endsWith.length() - i)) {
                return false;
            }
        }

        return true;

    }

    protected Wire createWriteFor(Bytes bytes) {

        if (TEXT_WIRE.contentEquals(preferredWireType))
            return new TextWire(bytes);

        if (BINARY_WIRE.contentEquals(preferredWireType))
            return new BinaryWire(bytes);

        if (RAW_WIRE.contentEquals(preferredWireType))
            return new RawWire(bytes);

        throw new IllegalStateException("preferredWireType=" + preferredWireType + " is not supported.");

    }

    @Override
    public void add(WireHandler handler) {
        handlers.add(handler);
    }


    class CoreWireHandler implements WireHandler {

        public void process(Wire in, Wire out) {

            long tid = inWire.read(CoreFields.tid).int64();
            outWire.write(CoreFields.tid).int64(tid);

            in.readEventName(cspText);

            if ("getWireFormats".contentEquals(cspText)) {
                out.write(CoreFields.reply).text(TEXT_WIRE + "," + BINARY_WIRE);
                return;
            }

            if ("setWireFormat".contentEquals(cspText)) {
                out.write(CoreFields.reply).text(preferredWireType);
                recreateWire(true);
            }

        }

    }

}