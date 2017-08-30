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
package com.jagrosh.giveawaybot.entities;

import com.jagrosh.giveawaybot.Constants;
import com.jagrosh.giveawaybot.database.DatabaseConnector;
import com.jagrosh.giveawaybot.rest.RestJDA;
import com.jagrosh.giveawaybot.util.FormatUtil;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageReaction;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.exceptions.ErrorResponseException;
import net.dv8tion.jda.core.utils.MiscUtil;
import net.dv8tion.jda.core.utils.SimpleLog;

import java.awt.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Giveaway {

    public static final SimpleLog LOG = SimpleLog.getLog("REST");

    public final long messageId;
    public final long channelId;
    public final long guildId;
    public final Instant end;
    public final int winners;
    public final String prize;

    public Giveaway(long messageId, long channelId, long guildId, Instant end, int winners, String prize) {
        this.messageId = messageId;
        this.channelId = channelId;
        this.guildId = guildId;
        this.end = end;
        this.winners = winners;
        this.prize = prize == null ? null : prize.isEmpty() ? null : prize;
    }

    public static <T> List<T> selectWinners(List<T> list, int winners) {
        List<T> winlist = new LinkedList<>();
        List<T> pullist = new LinkedList<>(list);
        for (int i = 0; i < winners && !pullist.isEmpty(); i++) {
            winlist.add(pullist.remove((int) (Math.random() * pullist.size())));
        }
        return winlist;
    }

    public static void getWinners(Message message, Consumer<List<User>> success, Runnable failure) {
        try {
            MessageReaction mr = message.getReactions().stream().filter(r -> r.getEmote().getName().equals(Constants.TADA)).findAny().orElse(null);
            mr.getUsers(100).queue(u -> {
                List<User> users = new LinkedList<>();
                users.addAll(u);
                users.remove(mr.getJDA().getSelfUser());
                if (users.isEmpty())
                    failure.run();
                else {
                    int wincount;
                    String[] split = message.getEmbeds().get(0).getFooter().getText().split(" ");
                    try {
                        wincount = Integer.parseInt(split[0]);
                    } catch (NumberFormatException e) {
                        wincount = 1;
                    }
                    List<User> wins = new LinkedList<>();
                    for (int i = 0; i < wincount && !users.isEmpty(); i++) {
                        wins.add(users.remove((int) (Math.random() * users.size())));
                    }
                    success.accept(wins);
                }
            }, f -> failure.run());
        } catch (Exception e) {
            failure.run();
        }
    }

    public Message render(Color color, Instant now) {
        MessageBuilder mb = new MessageBuilder();
        boolean close = now.plusSeconds(6).isAfter(end);
        mb.append(Constants.YAY).append(close ? " **G I V E A W A Y** " : "   **GIVEAWAY**   ").append(Constants.YAY);
        EmbedBuilder eb = new EmbedBuilder();
        if (close)
            eb.setColor(Color.RED);
        else if (color == null)
            eb.setColor(Constants.BLURPLE);
        else
            eb.setColor(color);
        eb.setFooter((winners == 1 ? "" : winners + " Winners | ") + "Ends at", null);
        eb.setTimestamp(end);
        eb.setDescription("React with " + Constants.TADA + " to enter!\nTime remaining: " + FormatUtil.secondsToTime(now.until(end, ChronoUnit.SECONDS)));
        if (prize != null)
            eb.setAuthor(prize, null, null);
        if (close)
            eb.setTitle("Last chance to enter!!!", null);
        mb.setEmbed(eb.build());
        return mb.build();
    }

    public void update(RestJDA restJDA, DatabaseConnector connector, Instant now) {
        restJDA.editMessage(Long.toString(channelId), Long.toString(messageId), render(connector.settings.getSettings(guildId).color, now)).queue(m -> {
        }, t -> {
            if (t instanceof ErrorResponseException) {
                ErrorResponseException e = (ErrorResponseException) t;
                switch (e.getErrorCode()) {
                    // delete the giveaway, since the bot wont be able to have access again
                    case 10008: // message not found
                    case 10003: // channel not found
                        connector.giveaways.deleteGiveaway(messageId);
                        break;

                    // for now, just keep chugging, maybe we'll get perms back
                    case 50001: // missing access
                    case 50013: // missing permissions
                        break;

                    // anything else, print it out
                    default:
                        LOG.warn("RestAction returned error: " + e.getErrorCode() + ": " + e.getMeaning());
                }
            } else
                LOG.fatal("RestAction failure: [" + t + "] " + t.getMessage());
        });
    }

    public void end(RestJDA restJDA) {
        MessageBuilder mb = new MessageBuilder();
        mb.append(Constants.YAY).append(" **GIVEAWAY ENDED** ").append(Constants.YAY);
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(1));
        eb.setFooter((winners == 1 ? "" : winners + " Winners | ") + "Ended at", null);
        eb.setTimestamp(end);
        if (prize != null)
            eb.setAuthor(prize, null, null);
        restJDA.getReactionUsers(Long.toString(channelId), Long.toString(messageId), MiscUtil.encodeUTF8(Constants.TADA)).cache(true).queue(ids -> {
            List<Long> wins = selectWinners(ids, winners);
            String toSend;
            if (wins.isEmpty()) {
                eb.setDescription("Could not determine a winner!");
                toSend = "A winner could not be determined!";
            } else if (wins.size() == 1) {
                eb.setDescription("Winner: <@" + wins.get(0) + ">");
                toSend = "Congratulations <@" + wins.get(0) + ">! You won" + (prize == null ? "" : " the **" + prize + "**") + "!";
            } else {
                eb.setDescription("Winners:");
                wins.forEach(w -> eb.appendDescription("\n").appendDescription("<@" + w + ">"));
                toSend = "Congratulations <@" + wins.get(0) + ">";
                for (int i = 1; i < wins.size(); i++)
                    toSend += ", <@" + wins.get(i) + ">";
                toSend += "! You won" + (prize == null ? "" : " the **" + prize + "**") + "!";
            }
            mb.setEmbed(eb.build());
            restJDA.editMessage(Long.toString(channelId), Long.toString(messageId), mb.build()).queue();
            restJDA.sendMessage(Long.toString(channelId), toSend).queue();
        }, v -> {
            eb.setDescription("Could not determine a winner!");
            mb.setEmbed(eb.build());
            restJDA.editMessage(Long.toString(channelId), Long.toString(messageId), mb.build()).queue();
            restJDA.sendMessage(Long.toString(channelId), "A winner could not be determined!").queue();
        });
    }
}
