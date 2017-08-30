/*
 * Copyright 2017 John Grosh (john.a.grosh@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.giveawaybot;

import com.jagrosh.giveawaybot.database.DatabaseConnector;
import com.jagrosh.giveawaybot.entities.Status;
import com.jagrosh.giveawaybot.rest.RestJDA;
import net.dv8tion.jda.core.utils.SimpleLog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Updater {

    /**
     * Runs the application as a giveaway updater
     *
     * @throws Exception
     */
    public static void main() throws Exception {
        // load tokens from a file
        // 0 - bot token
        // 1 - database host
        // 2 - database username
        // 3 - database pass
        List<String> tokens = Files.readAllLines(Paths.get("updater.txt"));

        // connects to the database
        DatabaseConnector database = new DatabaseConnector(tokens.get(1), tokens.get(2), tokens.get(3));

        // migrate the old giveaways if the file exists
        migrateGiveaways(database);

        // make a 'JDA' rest client
        RestJDA restJDA = new RestJDA(tokens.get(0));

        // make a pool to run the update loop
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor();

        // create an index to track time
        AtomicLong index = new AtomicLong(0);

        pool.scheduleWithFixedDelay(() -> {
            // set vars for this iteration
            long current = index.getAndIncrement();
            Instant now = Instant.now();

            // end giveaways with end status
            database.giveaways.getGiveaways(Status.ENDNOW).forEach(giveaway -> {
                database.giveaways.deleteGiveaway(giveaway.messageId);
                giveaway.end(restJDA);
            });

            // end giveaways that have run out of time
            database.giveaways.getGiveawaysEndingBefore(now.plusMillis(1900)).forEach(giveaway -> {
                database.giveaways.deleteGiveaway(giveaway.messageId);
                giveaway.end(restJDA);
            });

            if (current % 300 == 0) {
                // update all giveaways
                database.giveaways.getGiveaways().forEach(giveaway -> giveaway.update(restJDA, database, now));
            } else if (current % 60 == 0) {
                // update giveaways within 1 hour of ending
                database.giveaways.getGiveawaysEndingBefore(now.plusSeconds(60 * 60)).forEach(giveaway -> giveaway.update(restJDA, database, now));
            } else if (current % 5 == 0) {
                // update giveaways within 3 minutes of ending
                database.giveaways.getGiveawaysEndingBefore(now.plusSeconds(3 * 60)).forEach(giveaway -> giveaway.update(restJDA, database, now));
            } else {
                // update giveaways within 10 seconds of ending
                database.giveaways.getGiveawaysEndingBefore(now.plusSeconds(6)).forEach(giveaway -> giveaway.update(restJDA, database, now));
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private static void migrateGiveaways(DatabaseConnector connector) {
        try {
            int[] count = {0};
            Files.readAllLines(Paths.get("giveaways_migration.txt"), Charset.forName("ISO-8859-1")).forEach(str -> {
                String[] parts = str.split("  ", 6);
                long guildid = Long.parseLong(parts[0]);
                long channelid = Long.parseLong(parts[1]);
                long messageid = Long.parseLong(parts[2]);
                Instant end = Instant.ofEpochSecond(Long.parseLong(parts[3]));
                int winners = Integer.parseInt(parts[4]);
                String prize = parts[5].equals("none") ? "" : (parts[5].length() > Constants.PRIZE_MAX ? parts[5].substring(0, Constants.PRIZE_MAX) : parts[5]);
                connector.giveaways.createGiveaway(guildid, channelid, messageid, end, winners, prize);
                count[0]++;
            });
            SimpleLog.getLog("Migration").info("Migrated " + count + " giveaways!");
        } catch (IOException e) {
            SimpleLog.getLog("Migration").fatal(e);
        }
    }
}
