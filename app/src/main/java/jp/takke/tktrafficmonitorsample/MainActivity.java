package jp.takke.tktrafficmonitorsample;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;


public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        {
            final Button button = (Button) findViewById(R.id.start_button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    final Intent service = new Intent(MainActivity.this, LayerService.class);
                    stopService(service);
                    startService(service);
                }
            });

            // auto start
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
//                    findViewById(R.id.sample_rx50_tx50_button).performClick();
                    findViewById(R.id.start_button).performClick();
                }
            }, 10);
        }

        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

                final int progress = seekBar.getProgress();
                restartWithPreview(progress);
            }
        });

        {
            final Button button = (Button) findViewById(R.id.stop_button);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopService(new Intent(MainActivity.this, LayerService.class));
                }
            });
        }

        final int[] sampleButtonIds = new int[]{R.id.sample_rx01_tx01_button,
                R.id.sample_rx20_tx20_button,
                R.id.sample_rx50_tx50_button,
                R.id.sample_rx80_tx80_button,
                R.id.sample_rx100_tx100_button
        };
        final int[] samples = new int[]{1, 20, 50, 80, 100};

        for (int i = 0; i < sampleButtonIds.length; i++) {

            final Button button = (Button) findViewById(sampleButtonIds[i]);
            final int kb = samples[i];

            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    restartWithPreview(kb);
                }
            });
        }
    }


    private void restartWithPreview(long kb) {

        final Intent service = new Intent(MainActivity.this, LayerService.class);

        service.putExtra("PREVIEW_RX_KB", kb);
        service.putExtra("PREVIEW_TX_KB", kb);

        stopService(service);
        startService(service);

        final SeekBar seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setProgress((int) kb);

    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
//        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
