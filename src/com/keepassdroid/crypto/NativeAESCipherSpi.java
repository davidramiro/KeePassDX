/*
 * Copyright 2009 Brian Pellin.
 *     
 * This file is part of KeePassDroid.
 *
 *  KeePassDroid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDroid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepassdroid.crypto;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.HashMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;

import android.util.Log;

public class NativeAESCipherSpi extends CipherSpi {
	
	private static boolean mIsStaticInit = false;
	private static HashMap<PhantomReference<NativeAESCipherSpi>, Long> mCleanup = new HashMap<PhantomReference<NativeAESCipherSpi>, Long>();
	private static ReferenceQueue<NativeAESCipherSpi> mQueue = new ReferenceQueue<NativeAESCipherSpi>();
	
	private final int AES_BLOCK_SIZE = 16;
	private byte[] mIV;
	
	private boolean mIsInited = false;
	private boolean mEncrypting = false;
	private long mCtxPtr;
	
	private int mBuffered;
	
	private static void staticInit() {
		mIsStaticInit = true;
		
		// Start the cipher context cleanup thread to run forever
		(new Thread(new Cleanup())).start();
	}
	
	private static void addToCleanupQueue(NativeAESCipherSpi ref, long ptr) {
		Log.d("KeepassDroid", "queued cipher context: " + ptr);
		mCleanup.put(new PhantomReference<NativeAESCipherSpi>(ref, mQueue), ptr);
	}
	
	/** Work with the garbage collector to clean up openssl memory when the cipher
	 *  context is garbage collected.
	 * @author bpellin
	 *
	 */
	private static class Cleanup implements Runnable {

		public void run() {
			while (true) {
				try {
					Reference<? extends NativeAESCipherSpi> ref = mQueue.remove();
					
					long ctx = mCleanup.remove(ref);
					nCleanup(ctx);
					Log.d("KeePassDroid", "Cleaned up cipher context: " + ctx);
					
				} catch (InterruptedException e) {
					// Do nothing, but resume looping if mQueue.remove is interrupted
				}
			}
		}
		
	}
	
	private static native void nCleanup(long ctxPtr);

	public NativeAESCipherSpi() {
		if ( ! mIsStaticInit ) {
			staticInit();
		}
	}
	
	@Override
	protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
			throws IllegalBlockSizeException, BadPaddingException {
		int maxSize = engineGetOutputSize(inputLen);
		byte[] output = new byte[maxSize];
		
		int finalSize = doFinal(input, inputOffset, inputLen, output, 0);
		
		if ( maxSize == finalSize ) {
			return output;
		} else {
			// TODO: Special doFinal to avoid this copy
			byte[] exact = new byte[finalSize];
			System.arraycopy(output, 0, exact, 0, finalSize);
			return exact;
		}
	}

	@Override
	protected int engineDoFinal(byte[] input, int inputOffset, int inputLen,
			byte[] output, int outputOffset) throws ShortBufferException,
			IllegalBlockSizeException, BadPaddingException {
		
		int result = doFinal(input, inputOffset, inputLen, output, outputOffset);
		
		if ( result == -1 ) {
			throw new ShortBufferException();
		}
		
		return result;
	}
	
	private int doFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
		
		int outputSize = engineGetOutputSize(inputLen);
		
		int updateAmt =	nativeUpdateWrap(mCtxPtr, input, inputOffset, inputLen, output, outputOffset, outputSize);
		
		int finalAmt = nativeDoFinalWrap(mCtxPtr, output, outputOffset + updateAmt, outputSize - updateAmt); 
		
		int out = updateAmt + finalAmt;
		
		mBuffered = 0;
		
		return out;
	}
	
	private int nativeDoFinalWrap(long ctxPtr, byte[] output, int outputOffest, int outputSize) {
		if (mEncrypting) {
			return nEncryptFinal(ctxPtr, output, outputOffest, outputSize);
		} else {
			return nDecryptFinal(ctxPtr, output, outputOffest, outputSize);
		}
		
	}
	private native int nEncryptFinal(long ctxPtr, byte[] output, int outputOffest, int outputSize);
	private native int nDecryptFinal(long ctxPtr, byte[] output, int outputOffest, int outputSize);

	@Override
	protected int engineGetBlockSize() {
		return AES_BLOCK_SIZE;
	}

	@Override
	protected byte[] engineGetIV() {
		return mIV.clone();
	}

	@Override
	protected int engineGetOutputSize(int inputLen) {
		int totalLen = mBuffered + inputLen;

		int padLen = AES_BLOCK_SIZE - (totalLen % AES_BLOCK_SIZE);

		// TODO: Round up to nearest full block (there's probably a better way to do this)
		return totalLen + padLen;
	}

	@Override
	protected AlgorithmParameters engineGetParameters() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected void engineInit(int opmode, Key key, SecureRandom random)
			throws InvalidKeyException {

		byte[] ivArray = new byte[16];
		random.nextBytes(ivArray);
		
		init(opmode, key, new IvParameterSpec(ivArray));
	}

	@Override
	protected void engineInit(int opmode, Key key,
			AlgorithmParameterSpec params, SecureRandom random)
			throws InvalidKeyException, InvalidAlgorithmParameterException {
		
		IvParameterSpec ivparam;
		
		if ( params instanceof IvParameterSpec ) {
			ivparam = (IvParameterSpec) params;
		} else {
			throw new InvalidAlgorithmParameterException("params must be an IvParameterSpec.");
		}
		
		init(opmode, key, ivparam);
	}
	

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameters params,
			SecureRandom random) throws InvalidKeyException,
			InvalidAlgorithmParameterException {
		
		try {
			engineInit(opmode, key, params.getParameterSpec(AlgorithmParameterSpec.class), random);
		} catch (InvalidParameterSpecException e) {
			throw new InvalidAlgorithmParameterException(e);
		}

	}

	private void init(int opmode, Key key, IvParameterSpec params) {
		if ( mIsInited ) {
			// Do not allow multiple inits
			assert(true);
			throw new RuntimeException("Don't allow multiple inits");
		} else {
			NativeLib.init();
			mIsInited = true;
		}
		
		mIV = params.getIV();
		mEncrypting = opmode == Cipher.ENCRYPT_MODE;
		mBuffered = 0;
		mCtxPtr = nativeInitWrap(key.getEncoded(), mIV);
		addToCleanupQueue(this, mCtxPtr);
	}
	
	private long nativeInitWrap(byte[] key, byte[] iv) {
		if (mEncrypting) {
			return nEncryptInit(key, iv);
		} else {
			return nDecryptInit(key, iv);
		}
		
	}
	
	private native long nEncryptInit(byte[] key, byte[] iv);
	private native long nDecryptInit(byte[] key, byte[] iv);
	
	@Override
	protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
		if ( ! mode.equals("CBC") ) {
			throw new NoSuchAlgorithmException("This only supports CBC mode");
		}
	}

	@Override
	protected void engineSetPadding(String padding)
			throws NoSuchPaddingException {
		
		if ( ! mIsInited ) {
			NativeLib.init();
		}
		
		if ( padding.length() == 0 ) {
			return;
		}

		if ( ! padding.equals("PKCS5Padding") ) {
			throw new NoSuchPaddingException("Only supports PKCS5Padding.");
		}
			
	}
	
	@Override
	protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
		int maxSize = engineGetOutputSize(inputLen);
		byte output[] = new byte[maxSize];
		
		int updateSize = update(input, inputOffset, inputLen, output, 0);
		
		if ( updateSize == maxSize ) {
			return output;
		} else {
			// TODO: We could optimize update for this case to avoid this extra copy
			byte[] exact = new byte[updateSize];
			System.arraycopy(output, 0, exact, 0, updateSize);
			return exact;
		}
		
	}

	@Override
	protected int engineUpdate(byte[] input, int inputOffset, int inputLen,
			byte[] output, int outputOffset) throws ShortBufferException {
		
		int result = update(input, inputOffset, inputLen, output, outputOffset);
		
		if ( result == -1 ) {
			throw new ShortBufferException("Insufficient buffer.");
		}
		
		return result;
		
	}
	
	int update(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
		int outputSize = engineGetOutputSize(inputLen);
		
		int out = nativeUpdateWrap(mCtxPtr, input, inputOffset, inputLen, output, outputOffset, outputSize);
		
		mBuffered = (mBuffered + ((inputLen - out))) % AES_BLOCK_SIZE;
		
		return out;
		
		
	}
	
	private int nativeUpdateWrap(long ctxPtr, byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset, int outputSize) {
		if (mEncrypting) {
			return nEncryptUpdate(ctxPtr, input, inputOffset, inputLen, output, outputOffset, outputSize);
		} else {
			return nDecryptUpdate(ctxPtr, input, inputOffset, inputLen, output, outputOffset, outputSize);
		}
	}
	
	private native int nEncryptUpdate(long ctxPtr, byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset, int outputSize);
	private native int nDecryptUpdate(long ctxPtr, byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset, int outputSize);
	
}