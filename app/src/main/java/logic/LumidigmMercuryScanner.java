package logic;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.openandid.core.Controller;
import com.openandid.core.ScanningActivity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashMap;

public class LumidigmMercuryScanner extends Scanner {

    /*
      This class is the result of reverse engineering scanner protocols from wireshark-sniffed packets.
      It's terrible. Try not to do things like this in the future. Get an official driver to work from.
     */

    private static final String TAG = "LumidigmDriver";

    private static final HashMap<String, int[]> COMMANDS = new HashMap<>();

    //Load byte_phrase dictionary for scanner commands
    static {
        COMMANDS.put("mp1", new int[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x52, 0x00, 0x00, 0x00});
        COMMANDS.put("mp2", new int[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00});
        COMMANDS.put("mp3", new int[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00});
        COMMANDS.put("arm_trigger", new int[]{0x0d, 0x56, 0x47, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        //Templating turned ON, timeout to 60.

        int[] set_str =
                {0x0d, 0x56, 0x4d, 0x00, 0x00, 0x00, 0x00, 0x00, 0x44, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x3c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
                        0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x11, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x03, 0x00,
                        0x00, 0x00, 0x2c, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x03, 0x00};

        int[] set_str_inst =
                {0x0d, 0x56, 0x4d, 0x00, 0x00, 0x00, 0x00, 0x00, 0x44, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x0f, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
                        0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x11, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x03, 0x00,
                        0x00, 0x00, 0x2c, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00};
        COMMANDS.put("set_config", set_str);
        COMMANDS.put("set_config_instant", set_str_inst);
        COMMANDS.put("get_config", new int[]{0x0d, 0x56, 0x49, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0xc8, 0x00});
        COMMANDS.put("get_command", new int[]{0x0d, 0x56, 0x4c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        COMMANDS.put("get_busy", new int[]{0x0d, 0x56, 0x48, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        COMMANDS.put("get_status", new int[]{0x0d, 0x56, 0x4a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        COMMANDS.put("get_image", new int[]{0x0d, 0x56, 0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        COMMANDS.put("put_finished", new int[]{0x0d, 0x56, 0x85, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0e, 0x00});
        COMMANDS.put("get_template", new int[]{0x0d, 0x56, 0x45, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x05});
    }

    private static TriggerType currentTrigger = null;

    public LumidigmMercuryScanner(UsbDevice device, UsbManager manager) {
        super(device, manager);
        makeReady();
    }

    private String hexEncode(byte[] in) {
        StringBuilder sb = new StringBuilder();
        for (byte b : in) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @Override
    public void makeReady() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    inInit = true;
                    usbInterface = usbDevice.getInterface(0);
                    Log.i("Endpoint Count", Integer.toString(usbInterface.getEndpointCount()));

                    in = null;
                    out = null;

                    for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                        if (usbInterface.getEndpoint(i).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (usbInterface.getEndpoint(i).getDirection() == UsbConstants.USB_DIR_IN) {
                                Log.i("USB Connection", "EP_IN Found @ " + Integer.toString(i));
                                in = usbInterface.getEndpoint(i);
                                Log.i("epIN_INFO", Integer.toHexString(in.getAddress()));
                            } else {
                                Log.i("USB Connection", "EP_OUT Found @ " + Integer.toString(i));
                                out = usbInterface.getEndpoint(i);
                                Log.i("epOUT_INFO", Integer.toHexString(out.getAddress()));
                            }

                        }
                    }
                    Log.i("Scanner Ops", "Starting Init Process");

                    int k = 0;

                    byte[] comm = getCommand("get_command");
                    int c = 0;
                    while (true) {
                        try {
                            k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                            Log.i(TAG, String.format("get_command length %s bytes", k));
                            break;
                        } catch (Exception e) {
                            c += 1;
                            Log.i(TAG, "Init Failure " + Integer.toString(c));
                            Thread.sleep(250);
                            if (devConn != null) {
                                Log.e(TAG, "Connection isn't null... STRANGE!");
                            } else {
                                Log.e(TAG, "Connection is null.");
                            }
                            e.printStackTrace();
                            if (c > 3) {
                                throw new NullPointerException();
                            } else {
                                Log.i(TAG, "Flushing Buffer");
                                Controller.mDevice = ScanningActivity.getFreeDevice();
                                usbDevice = Controller.mDevice;
                                devConn = usbManager.openDevice(usbDevice);
                                Log.i(TAG, "Trying init again");
                            }

                        }
                    }
                    setupScanner(false);
                    inInit = false;
                    setReady(true);
                } catch (Exception e) {
                    scanCancelled = true;
                    Log.i(TAG, "INIT FAILED");
                    inInit = false;
                    e.printStackTrace();
                }

            }
        }).start();

    }

    private void setupScanner(boolean instant) {
        byte[] read_buffer;
        int k = 0;
        try {
            read_buffer = new byte[k];
        } catch (Exception e) {
            read_buffer = new byte[1024];
            e.printStackTrace();
        }

        devConn.bulkTransfer(in, read_buffer, k, 1000);
        read_buffer = new byte[1024];
        devConn.bulkTransfer(in, read_buffer, 512, 1000);
        read_buffer = new byte[1024];

        byte[] comm = getCommand("mp1");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        if (instant) {
            comm = getCommand("set_config_instant");
            currentTrigger = TriggerType.INSTANT;
        } else {
            comm = getCommand("set_config");
            currentTrigger = TriggerType.TRIGGERED;
        }

        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        read_buffer = new byte[1024];
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        read_buffer = new byte[1024];

        comm = getCommand("mp3");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);

        comm = getCommand("get_config");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        read_buffer = new byte[1024];
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        //read_buffer = new byte[1024];
        Log.i("Scanner Ops", "Finished Settings");
    }

    @Override
    public boolean scan(final String finger) {
        return scan(finger, true);
    }

    @Override
    public boolean scan(final String finger, boolean triggered) {
        if (!isReady()) {
            return false;
        }
        setReady(false);
        scanCancelled = false;
        Scanner.fingerSensed = false;

        if (triggered && currentTrigger.equals(TriggerType.INSTANT)) {
            setupScanner(false);
        } else if (!triggered && currentTrigger.equals(TriggerType.TRIGGERED)) {
            setupScanner(true);
        }
        //MUST be run in the background with getImage returning the bitmap to the GUI in the onfinish method if the scan is successful.

        int k = 0;
        byte[] read_buffer = new byte[1024];

        byte[] comm = getCommand("mp2");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        //Log.i("len", Integer.toString(k));

        comm = getCommand("arm_trigger");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        //Log.i("len", Integer.toString(k));
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        read_buffer = new byte[1024];
        devConn.bulkTransfer(in, read_buffer, 1024, 1000);
        read_buffer = new byte[1024];

        comm = getCommand("mp2");
        k = devConn.bulkTransfer(out, comm, comm.length, 1000);
        //Log.i("len", Integer.toString(k));

        boolean taken = false;
        while (true) {
            if (scanCancelled) {
                return false;
            }
            if (((int) read_buffer[8] & 0xFF) == 4 && ((int) read_buffer[12] & 0xFF) == 1 && !taken) {
                taken = true;
                Log.i("scanner status", "finger sensed");
                Scanner.fingerSensed = true;
            } else {
                Log.i(TAG, String.format("%s | %s | %s | %s", read_buffer[8], read_buffer[12], taken, read_buffer));
            }
            if (taken) {

                if (((int) read_buffer[8] & 0xFF) == 4 && ((int) read_buffer[12] & 0xFF) == 0) {
                    Log.i("scanner status", "image ready!");
                    Log.i("found status:", read_buffer.toString());
                    comm = getCommand("get_image");
                    k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                    //Log.i("length", Integer.toString(k));
                    read_buffer = new byte[1024];


                    //Phantom Data
                    k = devConn.bulkTransfer(in, read_buffer, 1024, 1000);

                    byte[] image_holder = new byte[0];
                    int last = 0;
                    int count = 0;
                    int read_size = 512;
                    while (true) {
                        k = devConn.bulkTransfer(in, read_buffer, read_size, 1000);
                        //Log.i("length", Integer.toString(k));
                        if (count == 192) {
                            count += 1;
                            int cur = last + 278;
                            byte[] tmp = new byte[last];
                            System.arraycopy(image_holder, 0, tmp, 0, image_holder.length);
                            image_holder = new byte[cur];
                            System.arraycopy(tmp, 0, image_holder, 0, last);
                            System.arraycopy(read_buffer, 0, image_holder, tmp.length, 278);
                            break;

                        }
                        count += 1;
                        int cur = (count * read_size);
                        byte[] tmp = new byte[last];
                        System.arraycopy(image_holder, 0, tmp, 0, image_holder.length);
                        image_holder = new byte[cur];
                        System.arraycopy(tmp, 0, image_holder, 0, last);
                        System.arraycopy(read_buffer, 0, image_holder, tmp.length, read_size);

                        last = cur;

                    }
                    new Thread(new Runnable() {
                        public void run() {
                            //Clean up on new thread so we don't lose time.
                            try {
                                byte[] read_buffer = new byte[1024];
                                byte[] comm = getCommand("mp2");
                                int k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                                comm = getCommand("put_finished");
                                k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                                k = devConn.bulkTransfer(in, read_buffer, 1024, 1000);
                                k = devConn.bulkTransfer(in, read_buffer, 1024, 1000);
                                //Old finish with //ready = true;
                                comm = null;
                                comm = getCommand("mp2");
                                k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                                comm = null;
                                comm = getCommand("get_template");
                                k = devConn.bulkTransfer(out, comm, comm.length, 1000);
                                //Junk read
                                k = devConn.bulkTransfer(in, read_buffer, 512, 1000);
                                //Template Read
                                k = devConn.bulkTransfer(in, read_buffer, 512, 1000);
                                //LOG output
                                Log.i(TAG, "Flushing the scanner's memory");
                                byte[] fake_buff = new byte[512];
                                for (int i = 0; i < 4; i++) {
                                    k = devConn.bulkTransfer(in, fake_buff, 512, 1000);
                                }
                                Log.i(TAG, "Done Flushing");
                                byte b_46 = Byte.parseByte("70", 10);
                                byte b_4d = Byte.parseByte("77", 10);
                                byte[] t_start = new byte[2];
                                int template_start_position = 0;
                                t_start[0] = b_46;
                                t_start[1] = b_4d;
                                //Try the shortcut first
                                if (Arrays.equals(Arrays.copyOfRange(read_buffer, 16, (18)), t_start)) {
                                    template_start_position = 16;
                                    Log.i(TAG, "WooHoo! Template start shortcut!");
                                }
                                //Ok now we have to hunt for the start of the template
                                else {
                                    for (int i = 0; i < 100; i++) {
                                        try {
                                            byte[] sub = Arrays.copyOfRange(read_buffer, i, (i + 2));
                                            if (Arrays.equals(sub, t_start)) {
                                                template_start_position = i;
                                                Log.i(TAG, "Start position found at: " + Integer.toString(i));
                                                break;
                                            } else {
                                                //Log.i("TAG",bytes_to_string(sub)+"!="+bytes_to_string(t_start));
                                            }
                                        } catch (Exception e) {
                                            Log.i(TAG, "error comparing bytes");
                                        }
                                    }
                                }
                                byte[] temp_header = Arrays.copyOfRange(read_buffer, template_start_position, (template_start_position + 31));
                                int minutia_count = temp_header[29];
                                int body_copy_length = (6 * minutia_count);
                                byte[] _temp_body = Arrays.copyOfRange(read_buffer, (template_start_position + 30), (template_start_position + 30 + body_copy_length));
                                byte[] temp_body = Arrays.copyOf(_temp_body, (_temp_body.length + 2));
                                //indicating no extended data
                                temp_body[temp_body.length - 1] = (byte) 0;
                                temp_body[temp_body.length - 2] = (byte) 0;
                                byte[] header_start = Arrays.copyOfRange(temp_header, 0, 8);
                                byte[] header_finish = Arrays.copyOfRange(temp_header, 14, 30);
                                int new_total_length = 28 + temp_body.length;
                                ByteBuffer b = ByteBuffer.allocate(4);
                                b.order(ByteOrder.BIG_ENDIAN);
                                b.putInt(new_total_length);
                                byte[] length_seq = b.array();
                                byte[] iso_template = new byte[new_total_length];
                                int point = 0;
                                System.arraycopy(header_start, 0, iso_template, 0, header_start.length);
                                point += header_start.length;
                                System.arraycopy(length_seq, 0, iso_template, point, length_seq.length);
                                point += length_seq.length;
                                System.arraycopy(header_finish, 0, iso_template, point, header_finish.length);
                                point += header_finish.length;
                                System.arraycopy(temp_body, 0, iso_template, point, temp_body.length);
                                String iso_min_template = hexEncode(iso_template);
                                setIsoTemplate(finger, iso_min_template);
                                Log.i("Template:", iso_min_template);
                                setReady(true);
                            } catch (Exception e) {
                                Log.i(TAG, "iso templating failed");
                                setIsoTemplate(finger, null);
                                setReady(true);
                                e.printStackTrace();
                            }
                        }
                    }).start();


                    byte[] final_image = new byte[98560];
                    System.arraycopy(image_holder, 20, final_image, 0, 98560);

                    image_holder = null;

                    //Convert byte array from scanner to appropriately colored int array for stupid bitmap setPixel method
                    int[] image_ints = new int[98560];

                    for (int i = 0; i < final_image.length; i++) {
                        int n = (final_image[i] & 0xff);
                        int c = Color.rgb(n, n, n);
                        image_ints[i] = c;
                    }

                    final_image = null;

                    Bitmap complete_image;
                    complete_image = Bitmap.createBitmap(280, 352, Bitmap.Config.RGB_565);

                    int pix = 0;

                    for (int y = 0; y < 352; y++) {
                        for (int x = 279; x >= 0; x--) {
                            complete_image.setPixel(x, y, (0xFFFFFF - image_ints[pix]));

                            pix += 1;
                        }
                    }

                    setImage(finger, complete_image);
                    Scanner.fingerSensed = false;
                    break;
                }
                Log.i(TAG, "Waiting for sensor");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                Log.i(TAG, "Waiting for finger");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            read_buffer = new byte[1024];
            comm = getCommand("get_busy");
            k = devConn.bulkTransfer(out, comm, comm.length, 1000);
            devConn.bulkTransfer(in, read_buffer, 1024, 1000);
            read_buffer = new byte[1024];
            devConn.bulkTransfer(in, read_buffer, 1024, 1000);
            comm = getCommand("mp2");
            k = devConn.bulkTransfer(out, comm, comm.length, 1000);
            if (((int) read_buffer[12] == 11)) {
                Log.e(TAG, "Scan Error!");
                return false;
            }
        }
        Log.i("Scanner Status", "Scan Finished");
        return true;
    }

    private byte[] getCommand(String key) {
        int[] int_phrase = COMMANDS.get(key);
        ByteBuffer messageBuff = ByteBuffer.allocate(int_phrase.length * 4);
        IntBuffer intBuff = messageBuff.asIntBuffer();
        intBuff.put(int_phrase);
        byte[] array = messageBuff.array();
        byte[] out = new byte[array.length / 4];
        int c = 0;
        for (int i = 3; i < array.length; i += 4) {
            out[c] = array[i];
            c += 1;
        }
        return out;
    }

    private enum TriggerType {
        INSTANT,
        TRIGGERED
    }
}
