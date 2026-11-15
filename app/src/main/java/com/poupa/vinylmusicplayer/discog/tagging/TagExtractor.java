package com.poupa.vinylmusicplayer.discog.tagging;

import android.content.Context;

import androidx.annotation.NonNull;

import com.poupa.vinylmusicplayer.App;
import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.model.Song;
import com.poupa.vinylmusicplayer.util.AutoCloseAudioFile;
import com.poupa.vinylmusicplayer.util.OopsHandler;
import com.poupa.vinylmusicplayer.util.SAFUtil;
import com.poupa.vinylmusicplayer.util.SafeToast;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.id3.ID3v22Frames;
import org.jaudiotagger.tag.id3.ID3v23Frames;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.ID3v2ChapterFrames;
import org.jaudiotagger.tag.id3.valuepair.ChannelTypes;
import org.jaudiotagger.tag.id3.valuepair.EventTimingTimestampTypes;
import org.jaudiotagger.tag.id3.valuepair.EventTimingTypes;
import org.jaudiotagger.tag.id3.valuepair.InterpolationTypes;
import org.jaudiotagger.tag.id3.valuepair.ReceivedAsTypes;
import org.jaudiotagger.tag.id3.valuepair.SynchronisedLyricsContentType;
import org.jaudiotagger.tag.id3.valuepair.TextEncoding;
import org.jaudiotagger.tag.lyrics3.Lyrics3v2Fields;
import org.jaudiotagger.tag.reference.GenreTypes;
import org.jaudiotagger.tag.reference.Languages;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.util.List;

/**
 * @author SC (soncaokim)
 */

public class TagExtractor {
    @FunctionalInterface
    private interface Func3Args<T1, T2, T3, R> {
        R apply(T1 arg1,T2 arg2,T3 arg3);
    }
    private static final Func3Args<Tag, FieldKey, String, String> safeGetTag = (tags, tag, defaultValue) -> {
        try {
            final String value = tags.getFirst(tag).trim();
            return (value.isEmpty()) ? defaultValue : value;
        }
        catch (KeyNotFoundException | UnsupportedOperationException ignored) {return defaultValue;}
    };
    private static final Func3Args<Tag, FieldKey, Integer, Integer> safeGetTagAsInteger = (tags, tag, defaultValue) -> {
        try {return Integer.parseInt(safeGetTag.apply(tags, tag, String.valueOf(defaultValue)));}
        catch (NumberFormatException ignored) {return defaultValue;}
    };
    private static final Func3Args<Tag, FieldKey, Integer, Integer> safeGetTagAsReleaseYear = (tags, tag, defaultValue) -> {
        try {
            String tagValueAsText = safeGetTag.apply(tags, tag, "");
            final int MINIMAL_LENGHT = 4; // yyyy
            if (tagValueAsText.length() < MINIMAL_LENGHT) {
                return defaultValue;
            }
            return Integer.parseInt(tagValueAsText.substring(0, MINIMAL_LENGHT));
        } catch (NumberFormatException ignored) {return defaultValue;}
    };
    private static final Func3Args<Tag, FieldKey, List<String>, List<String>> safeGetTagAsList = (tags, tag, defaultValue) -> {
        try {
            // An absent field reads as an empty list: keep the MediaStore value then, as
            // safeGetTag does for single values (an untagged recording keeps "<unknown>" artist)
            final List<String> values = MultiValuesTagUtil.splitIfNeeded(tags.getAll(tag));
            return values.isEmpty() ? defaultValue : values;
        }
        catch (KeyNotFoundException ignored) {return defaultValue;}
    };

    /**
     * Creates jaudiotagger's lazily initialized singletons on the calling thread, so that tags
     * can then be extracted on several threads without racing to create them (they are not
     * created under a lock). Cheap after the first call.
     */
    public static void prepareForParallelReads() {
        try {
            TagOptionSingleton.getInstance();
            AudioFileIO.getDefaultAudioFileIO();
            ID3v22Frames.getInstanceOf();
            ID3v23Frames.getInstanceOf();
            ID3v24Frames.getInstanceOf();
            ID3v2ChapterFrames.getInstanceOf();
            TextEncoding.getInstanceOf();
            GenreTypes.getInstanceOf();
            Languages.getInstanceOf();
            PictureTypes.getInstanceOf();
            ChannelTypes.getInstanceOf();
            EventTimingTimestampTypes.getInstanceOf();
            EventTimingTypes.getInstanceOf();
            InterpolationTypes.getInstanceOf();
            ReceivedAsTypes.getInstanceOf();
            SynchronisedLyricsContentType.getInstanceOf();
            Lyrics3v2Fields.getInstanceOf();
        } catch (@NonNull RuntimeException | NoClassDefFoundError e) {
            OopsHandler.collectStackTrace(e);
        }
    }

    public static void extractTags(@NonNull Song song) {
        final Context context = App.getStaticContext();
        try (AutoCloseAudioFile audio = SAFUtil.loadReadOnlyAudioFile(context, song)){
            if (audio == null) {
                // Cannot get the audio content
                SafeToast.show(context, context.getString(R.string.saf_read_failed, song.data));
                return;
            }
            // Override with metadata extracted from the file ourselves
            final AudioFile file = audio.get();
            Tag tags = file.getTagOrCreateAndSetDefault();
            if (tags.isEmpty() && (file instanceof MP3File)) {
                tags = ((MP3File) file).getID3v1Tag();
            }
            if (tags != null) {
                song.albumName = safeGetTag.apply(tags, FieldKey.ALBUM, song.albumName);
                song.artistNames = safeGetTagAsList.apply(tags, FieldKey.ARTIST, song.artistNames);
                song.albumArtistNames = safeGetTagAsList.apply(tags, FieldKey.ALBUM_ARTIST, song.albumArtistNames);
                song.title = safeGetTag.apply(tags, FieldKey.TITLE, song.title);

                String genres = safeGetTag.apply(tags, FieldKey.GENRE, "");
                song.genres = MultiValuesTagUtil.split(genres);

                song.discNumber = safeGetTagAsInteger.apply(tags, FieldKey.DISC_NO, song.discNumber);
                song.trackNumber = safeGetTagAsInteger.apply(tags, FieldKey.TRACK, song.trackNumber);
                song.year = safeGetTagAsReleaseYear.apply(tags, FieldKey.YEAR, song.year);
            }

            if (song.title.isEmpty()) {
                // fallback to use the file name
                song.title = file.getFile().getName();
            }

            ReplayGainTagExtractor.ReplayGainValues rgValues = ReplayGainTagExtractor.setReplayGainValues(file);
            song.replayGainAlbum = rgValues.album;
            song.replayGainTrack = rgValues.track;
            song.replayGainPeakAlbum = rgValues.peakAlbum;
            song.replayGainPeakTrack = rgValues.peakTrack;
        } catch (@NonNull Exception | NoSuchMethodError | VerifyError e) {
            OopsHandler.collectStackTrace(e);
        }
    }
}
