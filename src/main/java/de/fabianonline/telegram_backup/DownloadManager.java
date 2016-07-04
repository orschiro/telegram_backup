package de.fabianonline.telegram_backup;

import de.fabianonline.telegram_backup.UserManager;
import de.fabianonline.telegram_backup.Database;
import de.fabianonline.telegram_backup.StickerConverter;
import de.fabianonline.telegram_backup.DownloadProgressInterface;

import com.github.badoualy.telegram.api.TelegramClient;
import com.github.badoualy.telegram.tl.core.TLIntVector;
import com.github.badoualy.telegram.tl.core.TLObject;
import com.github.badoualy.telegram.tl.api.messages.TLAbsMessages;
import com.github.badoualy.telegram.tl.api.messages.TLAbsDialogs;
import com.github.badoualy.telegram.tl.api.*;
import com.github.badoualy.telegram.tl.api.upload.TLFile;
import com.github.badoualy.telegram.tl.exception.RpcErrorException;
import com.github.badoualy.telegram.tl.api.request.TLRequestUploadGetFile;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;

class DownloadManager {
	UserManager user;
	TelegramClient client;
	Database db;
	DownloadProgressInterface prog = null;
	
	public DownloadManager(UserManager u, TelegramClient c, DownloadProgressInterface p) {
		this.user = u;
		this.client = c;
		this.prog = p;
		this.db = new Database(u);
	}
	
	public void downloadMessages(Integer limit) throws RpcErrorException, IOException {
		System.out.println("Downloading most recent dialog... ");
		int max_message_id = client.messagesGetDialogs(
			0,
			0,
			new TLInputPeerEmpty(),
			1).getDialogs().get(0).getTopMessage();
		System.out.println("Top message ID is " + max_message_id);
		int max_database_id = db.getTopMessageID();
		System.out.println("Top message ID in database is " + max_database_id);
		if (limit != null) {
			System.out.println("Limit is set to " + limit);
			max_database_id = Math.max(max_database_id, max_message_id-limit);
			System.out.println("New top message id 'in database' is " + max_database_id);
		}
		
		int start_id = max_database_id + 1;
		int current_start_id = start_id;
		int end_id = max_message_id;
		if (start_id > end_id) {
			System.out.println("No new messages to download.");
			return;
		}
		
		prog.onMessageDownloadStart(end_id - current_start_id + 1);
		
		while (current_start_id <= end_id) {
			int my_end_id = Math.min(current_start_id+99, end_id);
			ArrayList<Integer> a = makeIdList(current_start_id, my_end_id);
			TLIntVector ids = new TLIntVector();
			ids.addAll(a);
			my_end_id = ids.get(ids.size()-1);
			current_start_id = my_end_id + 1;
			
			TLAbsMessages response = client.messagesGetMessages(ids);
			prog.onMessageDownloaded(response.getMessages().size());
			db.saveMessages(response.getMessages());
			db.saveChats(response.getChats());
			db.saveUsers(response.getUsers());
			try {
				Thread.sleep(Config.DELAY_AFTER_GET_MESSAGES);
			} catch (InterruptedException e) {}
		}
		
		prog.onMessageDownloadFinished();
	}
	
	public void downloadMedia() throws RpcErrorException, IOException {
		LinkedList<TLMessage> messages = this.db.getMessagesWithMedia();
		prog.onMediaDownloadStart(messages.size());
		for (TLMessage msg : messages) {
			TLAbsMessageMedia media = msg.getMedia();
			
			if (media instanceof TLMessageMediaPhoto) {
				this.downloadMessageMediaPhoto(msg, (TLMessageMediaPhoto)media);
			} else if (media instanceof TLMessageMediaDocument) {
				this.downloadMessageMediaDocument(msg, (TLMessageMediaDocument)media);
			} else if (media instanceof TLMessageMediaVideo) {
				this.downloadMessageMediaVideo(msg, (TLMessageMediaVideo)media);
			} else if (media instanceof TLMessageMediaAudio) {
				this.downloadMessageMediaAudio(msg, (TLMessageMediaAudio)media);
			} else if (media instanceof TLMessageMediaEmpty ||
				media instanceof TLMessageMediaUnsupported ||
				media instanceof TLMessageMediaGeo ||
				media instanceof TLMessageMediaWebPage ||
				media instanceof TLMessageMediaContact ||
				media instanceof TLMessageMediaVenue) {
				prog.onMediaDownloadedOther(true);
				// do nothing
			} else {
				throw new RuntimeException("Unexpected " + media.getClass().getName());
			}
		}
		prog.onMediaDownloadFinished();
	}
	
	private void downloadMessageMediaPhoto(TLMessage msg, TLMessageMediaPhoto p) throws RpcErrorException, IOException {
		if (p.getPhoto() instanceof TLPhoto) {
			TLPhoto photo = (TLPhoto) p.getPhoto();
			TLPhotoSize size = null;
			for (TLAbsPhotoSize s : photo.getSizes()) {
				if (s instanceof TLPhotoSize) {
					TLPhotoSize s2 = (TLPhotoSize) s;
					if (size == null || (s2.getW()>size.getW() && s2.getH()>size.getH())) {
						size = s2;
					}
				}
			}
			if (size==null) {
				throw new RuntimeException("Could not find a size for a photo.");
			}
			if (size.getLocation() instanceof TLFileLocation) {
				boolean res = this.downloadPhoto(msg.getId(), (TLFileLocation)size.getLocation(), size.getSize());
				prog.onMediaDownloadedPhoto(res);
			}
		} else if (p.getPhoto() instanceof TLPhotoEmpty) {
			downloadEmptyObject(p.getPhoto());
		} else {
			throw new RuntimeException("Got an unexpected " + p.getPhoto().getClass().getName());
		}
	}
	
	private void downloadMessageMediaDocument(TLMessage msg, TLMessageMediaDocument d) throws RpcErrorException, IOException {
		if (d.getDocument() instanceof TLDocument) {
			TLDocument doc = (TLDocument)d.getDocument();
			//Determine files extension
			String original_filename = null;
			TLDocumentAttributeSticker sticker = null;
			
			String ext = null;
			for(TLAbsDocumentAttribute attr : doc.getAttributes()) {
				if (attr instanceof TLDocumentAttributeFilename) {
					original_filename = ((TLDocumentAttributeFilename)attr).getFileName();
				} else if (attr instanceof TLDocumentAttributeSticker) {
					sticker = (TLDocumentAttributeSticker)attr;
				}
			}
			if (original_filename != null) {
				int i = original_filename.lastIndexOf('.');
				if (i>0) ext = original_filename.substring(i+1);
			}
			if (ext==null) {
				int i = doc.getMimeType().lastIndexOf('/');
				String type = doc.getMimeType().substring(i+1).toLowerCase();
				if (type.equals("unknown")) {
					ext = "dat";
				} else {
					ext = type;
				}
			}
			String filename;
			if (sticker != null) {
				filename = StickerConverter.makeFilenameWithPath(sticker);
			} else {
				filename = this.makeFilename(msg.getId(), ext);
			}
			
			boolean res = this.downloadDocument(filename, doc);
			if (sticker != null) {
				prog.onMediaDownloadedSticker(res);
			} else {
				prog.onMediaDownloadedDocument(res);
			}
		} else if (d.getDocument() instanceof TLDocumentEmpty) {
			downloadEmptyObject(d.getDocument());
		} else {
			throw new RuntimeException("Got an unexpected " + d.getDocument().getClass().getName());
		}
	}
	
	private void downloadMessageMediaVideo(TLMessage msg, TLMessageMediaVideo v) throws RpcErrorException, IOException {
		if (v.getVideo() instanceof TLVideo) {
			TLVideo vid = (TLVideo)v.getVideo();
			int i = vid.getMimeType().lastIndexOf('/');
			String ext = vid.getMimeType().substring(i+1).toLowerCase();
			boolean res = this.downloadVideo(this.makeFilename(msg.getId(), ext), vid);
			prog.onMediaDownloadedVideo(res);
		} else if (v.getVideo() instanceof TLVideoEmpty) {
			downloadEmptyObject(v.getVideo());
		} else {
			throw new RuntimeException("Got an unexpected " + v.getVideo().getClass().getName());
		}
	}
	
	private void downloadMessageMediaAudio(TLMessage msg, TLMessageMediaAudio a) throws RpcErrorException, IOException {
		if (a.getAudio() instanceof TLAudio) {
			TLAudio audio = (TLAudio)a.getAudio();
			int i = audio.getMimeType().lastIndexOf('/');
			String ext = audio.getMimeType().substring(i+1).toLowerCase();
			boolean res = this.downloadAudio(this.makeFilename(msg.getId(), ext), audio);
			prog.onMediaDownloadedAudio(res);
		} else if (a.getAudio() instanceof TLAudioEmpty) {
			downloadEmptyObject(a.getAudio());
		} else {
			throw new RuntimeException("Got an unexpected " + a.getAudio().getClass().getName());
		}
	}
	
	private ArrayList<Integer> makeIdList(int start, int end) {
		if (start > end) throw new RuntimeException("start and end reversed");
		ArrayList<Integer> a = new ArrayList<Integer>(end - start + 1);
		for (int i=0; i<=end-start; i++) a.add(start+i);
		return a; 
	}
	
	private boolean downloadPhoto(int msgId, TLFileLocation src, int size) throws RpcErrorException, IOException {
		TLInputFileLocation loc = new TLInputFileLocation();
		loc.setVolumeId(src.getVolumeId());
		loc.setLocalId(src.getLocalId());
		loc.setSecret(src.getSecret());
		
		return this.downloadFile(this.makeFilename(msgId, "jpg"), loc, size);
	}
	
	private boolean downloadDocument(String filename, TLDocument doc) throws RpcErrorException, IOException {
		TLInputDocumentFileLocation loc = new TLInputDocumentFileLocation();
		loc.setId(doc.getId());
		loc.setAccessHash(doc.getAccessHash());
		return this.downloadFileFromDc(filename, loc, doc.getDcId(), doc.getSize());
	}
	
	private boolean downloadVideo(String filename, TLVideo vid) throws RpcErrorException, IOException {
		TLInputDocumentFileLocation loc = new TLInputDocumentFileLocation();
		loc.setId(vid.getId());
		loc.setAccessHash(vid.getAccessHash());
		return this.downloadFileFromDc(filename, loc, vid.getDcId(), vid.getSize());
	}
	
	private boolean downloadAudio(String filename, TLAudio audio) throws RpcErrorException, IOException {
		TLInputDocumentFileLocation loc = new TLInputDocumentFileLocation();
		loc.setId(audio.getId());
		loc.setAccessHash(audio.getAccessHash());
		return this.downloadFileFromDc(filename, loc, audio.getDcId(), audio.getSize());
	}
	
	private void downloadEmptyObject(TLObject obj) {
		prog.onMediaDownloadedEmpty(true);
	}
	
	private String makeFilename(int id, String ext) {
		String path = this.user.getFileBase() + 
			Config.FILE_FILES_BASE +
			File.separatorChar;
		new File(path).mkdirs();
		if (ext!=null) return path + id + "." + ext;
		return path + id + ".dat";
	}
	
	private boolean downloadFile(String target, TLAbsInputFileLocation loc, int size) throws RpcErrorException, IOException {
		return downloadFileFromDc(target, loc, null, size);
	}
	
	private boolean downloadFileFromDc(String target, TLAbsInputFileLocation loc, Integer dcID, int size) throws RpcErrorException, IOException {
		// Don't download already existing files.
		if (new File(target).isFile()) return false;
		
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(target);
			int offset = 0;
			TLFile response;
			do {
				TLRequestUploadGetFile req = new TLRequestUploadGetFile(loc, offset, Config.FILE_DOWNLOAD_BLOCK_SIZE);
				if (dcID==null) {
					response = (TLFile)this.client.executeRpcQuery(req);
				} else {
					response = (TLFile) this.client.executeRpcQuery(req, dcID);
				}
				offset += response.getBytes().getData().length;
				fos.write(response.getBytes().getData());
				try { Thread.sleep(Config.DELAY_AFTER_GET_FILE); } catch(InterruptedException e) {}
			} while(offset < size && response.getBytes().getData().length>0);
			fos.close();
			if (offset < size) {
				System.out.println("Requested file " + target + " with " + size + " bytes, but got only " + offset + " bytes.");
				new File(target).delete();
				System.exit(1);
			}
			return true;
		} catch (java.io.IOException ex) {
			if (fos!=null) fos.close();
			new File(target).delete();
			System.out.println("IOException happened while downloading " + target);
			throw ex;
		} catch (RpcErrorException ex) {
			if (fos!=null) fos.close();
			new File(target).delete();
			System.out.println("RpcErrorException happened while downloading " + target);
			throw ex;
		}
	}
}
