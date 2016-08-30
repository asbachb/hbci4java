
/*  $Id: HBCIDialog.java,v 1.1 2011/05/04 22:37:46 willuhn Exp $

    This file is part of HBCI4Java
    Copyright (C) 2001-2008  Stefan Palme

    HBCI4Java is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    HBCI4Java is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.kapott.hbci.manager;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.kapott.hbci.GV.HBCIJobImpl;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.passport.HBCIPassportInternal;
import org.kapott.hbci.passport.HBCIPassportList;
import org.kapott.hbci.status.HBCIDialogStatus;
import org.kapott.hbci.status.HBCIInstMessage;
import org.kapott.hbci.status.HBCIMsgStatus;

/* @brief A class for managing exactly one HBCI-Dialog

    A HBCI-Dialog consists of a number of HBCI-messages. These
    messages will be sent (and the responses received) one
    after the other, without timegaps between them (to avoid
    network timeout problems).

    The messages generated by a HBCI-Dialog are at first DialogInit-Message,
    after that a message that contains one ore more "Geschaeftsvorfaelle"
    (i.e. the stuff that you really want to do via HBCI), and at last
    a DialogEnd-Message.

    In this class we have two API-levels, a mid-level API (for manually
    creating and processing dialogs) and a high-level API (for automatic
    creation of typical HBCI-dialogs). For each method the API-level is
    given in its description 
*/
public final class HBCIDialog
{
    private boolean     isAnon;
    private String      anonSuffix;
    private String      dialogid;  /* The dialogID for this dialog (unique for each dialog) */
    private long        msgnum;    /* An automatically managed message counter. */
    private  List<ArrayList<HBCIJobImpl>>        msgs;    /* this array contains all messages to be sent (excluding
                                             dialogInit and dialogEnd); each element of the arrayList
                                             is again an ArrayList, where each element is one
                                             task (GV) to be sent with this specific message */
    private Properties listOfGVs;    // liste aller GVs in der aktuellen msg; key ist der hbciCode des jobs,
                                     // value ist die anzahl dieses jobs in der aktuellen msg
    private HBCIHandler parentHandler;

    public HBCIDialog(HBCIHandler parentHandler)
    {
        HBCIUtils.log("creating new dialog",HBCIUtils.LOG_DEBUG);

        this.parentHandler=parentHandler;
        this.isAnon=((HBCIPassportInternal)parentHandler.getPassport()).isAnonymous();
        this.anonSuffix=isAnon?"Anon":"";
        this.msgs=new ArrayList<ArrayList<HBCIJobImpl>>();
        this.msgs.add(new ArrayList<HBCIJobImpl>());
        this.listOfGVs=new Properties();
    }
    
    public HBCIHandler getParentHandler()
    {
        return this.parentHandler;
    }

    /** @brief Processing the DialogInit stage and updating institute and user data from the server
               (mid-level API).

        This method processes the dialog initialization stage of an HBCIDialog. It creates
        a new rawMsg in the kernel and processes it. The return values will be
        passed to appropriate methods in the @c institute and @c user objects to
        update their internal state with the data received from the institute. */
    private HBCIMsgStatus doDialogInit()
    {
        HBCIMsgStatus ret=new HBCIMsgStatus();
        
        try {
            HBCIPassportInternal mainPassport=(HBCIPassportInternal)getParentHandler().getPassport();
            HBCIKernelImpl       kernel=(HBCIKernelImpl)getParentHandler().getKernel();
            
            // autosecmech
            HBCIUtils.log("checking whether passport is supported (but ignoring result)",HBCIUtils.LOG_DEBUG);
            boolean s=mainPassport.isSupported();
            HBCIUtils.log("passport supported: "+s,HBCIUtils.LOG_DEBUG);
            
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("STATUS_DIALOG_INIT"),HBCIUtils.LOG_INFO);
            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_INIT,null);
            String country=mainPassport.getCountry();
            String blz=mainPassport.getBLZ();
    
            boolean restarted=false;
            while (true) {
                kernel.rawNewMsg("DialogInit"+anonSuffix);
                kernel.rawSet("Idn.KIK.blz", blz);
                kernel.rawSet("Idn.KIK.country", country);
                if (!isAnon) {
                    kernel.rawSet("Idn.customerid", mainPassport.getCustomerId());
                    kernel.rawSet("Idn.sysid", mainPassport.getSysId());
                    String sysstatus=mainPassport.getSysStatus();
                    kernel.rawSet("Idn.sysStatus",sysstatus);
                    if (mainPassport.needInstKeys()) {
                        kernel.rawSet("KeyReq.SecProfile.method",mainPassport.getProfileMethod());
                        kernel.rawSet("KeyReq.SecProfile.version",mainPassport.getProfileVersion());
                        kernel.rawSet("KeyReq.KeyName.keytype", "V");
                        kernel.rawSet("KeyReq.KeyName.KIK.country", country);
                        kernel.rawSet("KeyReq.KeyName.KIK.blz", blz);
                        kernel.rawSet("KeyReq.KeyName.userid", mainPassport.getInstEncKeyName());
                        kernel.rawSet("KeyReq.KeyName.keynum", mainPassport.getInstEncKeyNum());
                        kernel.rawSet("KeyReq.KeyName.keyversion", mainPassport.getInstEncKeyVersion());

                        if (mainPassport.hasInstSigKey()) {
                            kernel.rawSet("KeyReq_2.SecProfile.method",mainPassport.getProfileMethod());
                            kernel.rawSet("KeyReq_2.SecProfile.version",mainPassport.getProfileVersion());
                            kernel.rawSet("KeyReq_2.KeyName.keytype", "S");
                            kernel.rawSet("KeyReq_2.KeyName.KIK.country", country);
                            kernel.rawSet("KeyReq_2.KeyName.KIK.blz", blz);
                            kernel.rawSet("KeyReq_2.KeyName.userid", mainPassport.getInstSigKeyName());
                            kernel.rawSet("KeyReq_2.KeyName.keynum", mainPassport.getInstSigKeyNum());
                            kernel.rawSet("KeyReq_2.KeyName.keyversion", mainPassport.getInstSigKeyVersion());
                        }
                    }
                }
                kernel.rawSet("ProcPrep.BPD", mainPassport.getBPDVersion());
                kernel.rawSet("ProcPrep.UPD", mainPassport.getUPDVersion());
                kernel.rawSet("ProcPrep.lang",mainPassport.getDefaultLang());
                kernel.rawSet("ProcPrep.prodName",HBCIUtils.getParam("client.product.name","HBCI4Java"));
                kernel.rawSet("ProcPrep.prodVersion",HBCIUtils.getParam("client.product.version","2.5"));
                ret=kernel.rawDoIt(!isAnon && HBCIKernelImpl.SIGNIT,
                        !isAnon && HBCIKernelImpl.CRYPTIT,
                        !isAnon && HBCIKernelImpl.NEED_SIG,
                        !isAnon && HBCIKernelImpl.NEED_CRYPT);

                boolean need_restart=mainPassport.postInitResponseHook(ret,isAnon);
                if (need_restart) {
                    HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                    if (restarted) {
                        HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                        throw new HBCI_Exception("*** restart loop - aborting");
                    }
                    restarted=true;
                } else {
                    break;
                }
            }
            
            Properties result=ret.getData();
            if (ret.isOK()) {
                HBCIInstitute inst=new HBCIInstitute(kernel,mainPassport,false);
                inst.updateBPD(result);
                inst.extractKeys(result);
    
                HBCIUser user=new HBCIUser(kernel,mainPassport,false);
                user.updateUPD(result);
               
                mainPassport.saveChanges();
    
                msgnum=2;
                dialogid=result.getProperty("MsgHead.dialogid");
                HBCIUtils.log("dialog-id set to "+dialogid,HBCIUtils.LOG_DEBUG);

                HBCIInstMessage msg=null;
                for (int i=0;true;i++) {
                    try {
                        String header=HBCIUtilsInternal.withCounter("KIMsg",i);
                        msg=new HBCIInstMessage(result,header);
                    } catch (Exception e) {
                        break;
                    }
                    HBCIUtilsInternal.getCallback().callback(mainPassport,
                                                     HBCICallback.HAVE_INST_MSG,
                                                     msg.toString(),
                                                     HBCICallback.TYPE_NONE,
                                                     new StringBuffer());
                }
            }

            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_INIT_DONE,new Object[] {ret,dialogid});
        } catch (Exception e) {
            ret.addException(e);
        }

        return ret;
    }
    
    private HBCIMsgStatus[] doJobs()
    {
        HBCIUtils.log(HBCIUtilsInternal.getLocMsg("LOG_PROCESSING_JOBS"),HBCIUtils.LOG_INFO);
        
        ArrayList<HBCIMsgStatus>        msgstatus_a=new ArrayList<HBCIMsgStatus>();
        HBCIPassportList msgPassports=new HBCIPassportList();
        
        HBCIKernelImpl       kernel=(HBCIKernelImpl)getParentHandler().getKernel();
        HBCIPassportInternal mainPassport=(HBCIPassportInternal)getParentHandler().getPassport();

        // durch die liste aller auszuf�hrenden nachrichten durchloopen
        int nof_messages=msgs.size();
        for (int j=0;j<nof_messages;j++) {
            // tasks ist liste aller jobs, die in dieser nachricht ausgef�hrt werden sollen
            ArrayList<HBCIJobImpl>     tasks= msgs.get(j);
            
            // loop wird benutzt, um zu z�hlen, wie oft bereits "nachgehakt" wurde,
            // falls ein bestimmter job nicht mit einem einzigen nachrichtenaustausch
            // abgearbeitet werden konnte (z.b. abholen kontoausz�ge)
            int           loop=0;
            HBCIMsgStatus msgstatus=new HBCIMsgStatus();
            
            // diese schleife loopt solange, bis alle jobs der aktuellen nachricht
            // tats�chlich abgearbeitet wurden (also inclusive "nachhaken")
            while (true) {
                boolean addMsgStatus=true;
                
                try {
                    HBCIUtils.log("generating custom msg #"+(j+1)+" (loop "+(loop+1)+")",
                        HBCIUtils.LOG_DEBUG);
                    
                    int taskNum=0;

                    msgPassports.clear();
                    kernel.rawNewMsg("CustomMsg");
                    
                    // durch alle jobs loopen, die eigentlich in der aktuellen
                    // nachricht abgearbeitet werden m�ssten
                    for (Iterator<HBCIJobImpl> i=tasks.iterator();i.hasNext();) {
                        HBCIJobImpl task=i.next();
                        
                        // wenn der Task entweder noch gar nicht ausgef�hrt wurde
                        // oder in der letzten Antwortnachricht ein entsprechendes
                        // Offset angegeben wurde
                        if (task.needsContinue(loop)) {
                            task.setContinueOffset(loop);
                            
                            Properties p=task.getLowlevelParams();
                            String header=HBCIUtilsInternal.withCounter("GV",taskNum);
                            
                            String taskName=task.getName();
                            HBCIUtils.log("adding task "+taskName,HBCIUtils.LOG_DEBUG);
                            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_SEND_TASK,task);
                            task.setIdx(taskNum);
                            
                            // Daten f�r den Task festlegen
                            for (Enumeration e=p.keys();e.hasMoreElements();) {
                                String key=(String)(e.nextElement());
                                kernel.rawSet(header+"."+key,p.getProperty(key));
                            }
                            
                            // additional passports f�r diesen task ermitteln
                            // und zu den passports f�r die aktuelle nachricht
                            // hinzuf�gen;
                            // doppelg�nger werden schon von 
                            // HBCIPassportList.addPassport() herausgefiltert
                            msgPassports.addAll(task.getSignaturePassports());
                            
                            taskNum++;
                        }
                    }
                    
                    // wenn keine jobs f�r die aktuelle message existieren
                    if (taskNum==0) {
                        HBCIUtils.log(
                                "loop "+(loop+1)+" aborted, because there are no more tasks to be executed",
                                HBCIUtils.LOG_DEBUG);
                        addMsgStatus=false;
                        break;
                    }
                    
                    kernel.rawSet("MsgHead.dialogid", dialogid);
                    kernel.rawSet("MsgHead.msgnum", getMsgNum());
                    kernel.rawSet("MsgTail.msgnum", getMsgNum());
                    nextMsgNum();
                    
                    // nachrichtenaustausch durchf�hren
                    msgstatus=kernel.rawDoIt(msgPassports,HBCIKernelImpl.SIGNIT,HBCIKernelImpl.CRYPTIT,HBCIKernelImpl.NEED_SIG,HBCIKernelImpl.NEED_CRYPT);
                    Properties result=msgstatus.getData();
                    
                    // searching for first segment number that belongs to the custom_msg
                    // we look for entries like {"1","CustomMsg.MsgHead"} and so
                    // on (this data is inserted from the HBCIKernelImpl.rawDoIt() method),
                    // until we find the first segment containing a task
                    int offset=0;   // this specifies, how many segments precede the first task segment
                    for (offset=1;true;offset++) {
                        String path=result.getProperty(Integer.toString(offset));
                        if (path==null || path.startsWith("CustomMsg.GV")) {
                            if (path==null) { // wenn kein entsprechendes Segment gefunden, dann offset auf 0 setzen
                                offset=0;
                            }
                            break;
                        }
                    }
                    
                    if (offset!=0) {           
                        // f�r jeden Task die entsprechenden R�ckgabedaten-Klassen f�llen
                        // in fillOutStore wird auch "executed" fuer den jeweiligen Task auf true gesetzt.
                        for (Iterator<HBCIJobImpl> i=tasks.iterator();i.hasNext();) {
                            HBCIJobImpl task=i.next();
                            if (task.needsContinue(loop)) {
                                // nur wenn der auftrag auch tatsaechlich gesendet werden musste
                                try {
                                    task.fillJobResult(msgstatus,offset);
                                    HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_SEND_TASK_DONE,task);
                                } catch (Exception e) {
                                    msgstatus.addException(e);
                                }
                            }
                        }
                    }
                    
                    if (msgstatus.hasExceptions()) {
                        HBCIUtils.log("aborting current loop because of errors",HBCIUtils.LOG_ERR);
                        break;
                    }
                    
                    loop++;
                } catch (Exception e) {
                    msgstatus.addException(e);
                } finally {
                    if (addMsgStatus) {
                        msgstatus_a.add(msgstatus);
                    }
                }
            }
        }

        HBCIMsgStatus[] ret=new HBCIMsgStatus[0];
        if (msgstatus_a.size()!=0)
            ret=(msgstatus_a.toArray(ret));

        return ret;
    }

    /** @brief Processes the DialogEnd stage of an HBCIDialog (mid-level API).

        Works similarily to doDialogInit(). */
    private HBCIMsgStatus doDialogEnd()
    {
        HBCIMsgStatus ret=new HBCIMsgStatus();
        
        HBCIHandler          handler=getParentHandler();
        HBCIPassportInternal mainPassport=(HBCIPassportInternal)handler.getPassport();
        HBCIKernelImpl       kernel=(HBCIKernelImpl)handler.getKernel();
        
        try {
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("LOG_DIALOG_END"),HBCIUtils.LOG_INFO);
            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_END,null);
    
            kernel.rawNewMsg("DialogEnd"+anonSuffix);
            kernel.rawSet("DialogEndS.dialogid", dialogid);
            kernel.rawSet("MsgHead.dialogid", dialogid);
            kernel.rawSet("MsgHead.msgnum", getMsgNum());
            kernel.rawSet("MsgTail.msgnum", getMsgNum());
            nextMsgNum();
            ret=kernel.rawDoIt(!isAnon && HBCIKernelImpl.SIGNIT,
                               !isAnon && HBCIKernelImpl.CRYPTIT,
                               !isAnon && HBCIKernelImpl.NEED_SIG,
                               !isAnon && HBCIKernelImpl.NEED_CRYPT);

            HBCIUtilsInternal.getCallback().status(mainPassport,HBCICallback.STATUS_DIALOG_END_DONE,ret);
        } catch (Exception e) {
            ret.addException(e);
        }

        return ret;
    }

    /** f�hrt einen kompletten dialog mit allen zu diesem
        dialog gehoerenden nachrichten/tasks aus.

        bricht diese methode mit einer exception ab, so muessen alle
        nachrichten bzw. tasks, die noch nicht ausgef�hrt wurden, 
        von der aufrufenden methode neu erzeugt werden */
    public HBCIDialogStatus doIt()
    {
        try {
            HBCIUtils.log("executing dialog",HBCIUtils.LOG_DEBUG);
            HBCIDialogStatus ret=new HBCIDialogStatus();
            
            HBCIPassportInternal passport=(HBCIPassportInternal)getParentHandler().getPassport();
            
            // first call passports's before-dialog-hook
            passport.beforeCustomDialogHook(this);
            
            HBCIMsgStatus initStatus=doDialogInit();
            ret.setInitStatus(initStatus);
                
            // so that e.g. pintan-passports can patch the list of messages to 
            // be executed (needed for twostep-mech)
            passport.afterCustomDialogInitHook(this);
            
            if (initStatus.isOK()) {
                ret.setMsgStatus(doJobs());
                ret.setEndStatus(doDialogEnd());
            }
            
            return ret;
        } finally {
            reset();
        }
    }

    private void reset()
    {
        try {
            dialogid=null;
            msgnum=1;
            msgs=new ArrayList<ArrayList<HBCIJobImpl>>();
            msgs.add(new ArrayList<HBCIJobImpl>());
            listOfGVs.clear();
        } catch (Exception e) {
            HBCIUtils.log(e);
        }
    }
    
    public String getDialogID()
    {
        return dialogid;
    }

    public String getMsgNum()
    {
        return Long.toString(msgnum);
    }

    public void nextMsgNum()
    {
        msgnum++;
    }
    
    private int getTotalNumberOfGVSegsInCurrentMessage()
    {
        int total=0;
        
        for (Enumeration e=listOfGVs.keys(); e.hasMoreElements(); ) {
            String hbciCode=(String)e.nextElement();
            int    counter=Integer.parseInt(listOfGVs.getProperty(hbciCode));
            total+=counter;
        }
        
        HBCIUtils.log("there are currently "+total+" GV segs in this message", HBCIUtils.LOG_DEBUG);
        return total;
    }

    public void addTask(HBCIJobImpl job)
    {
        // TODO: hier evtl. auch �berpr�fen, dass nur jobs mit den gleichen
        // signatur-anforderungen (anzahl) in einer msg stehen
        
        try {
            HBCIUtils.log(HBCIUtilsInternal.getLocMsg("EXCMSG_ADDJOB",job.getName()),HBCIUtils.LOG_INFO);
            job.verifyConstraints();
            
            // check bpd.numgva here
            String hbciCode=job.getHBCICode();
            
            int    gva_counter=listOfGVs.size();
            String counter_st=listOfGVs.getProperty(hbciCode);
            int    gv_counter=(counter_st!=null)?Integer.parseInt(counter_st):0;
            int    total_counter=getTotalNumberOfGVSegsInCurrentMessage();
            
            gv_counter++;
            total_counter++;
            if (counter_st==null) {
                gva_counter++;
            }

            HBCIPassportInternal passport=(HBCIPassportInternal)getParentHandler().getPassport();
            
            // BPD: max. Anzahl GV-Arten
            int maxGVA=passport.getMaxGVperMsg();
            // BPD: max. Anzahl von Job-Segmenten eines bestimmten Typs
            int maxGVSegJob=job.getMaxNumberPerMsg();        
            // Passport: evtl. weitere Einschr�nkungen bzgl. der Max.-Anzahl 
            // von Auftragssegmenten pro Nachricht
            int maxGVSegTotal=passport.getMaxGVSegsPerMsg();  
            
            if ((maxGVA>0 && gva_counter>maxGVA) || 
                    (maxGVSegJob>0 && gv_counter>maxGVSegJob) ||
                    (maxGVSegTotal>0 && total_counter>maxGVSegTotal)) 
            {
                if (maxGVSegTotal>0 && total_counter>maxGVSegTotal) {
                    HBCIUtils.log(
                            "have to generate new message because current type of passport only allows "+maxGVSegTotal+" GV segs per message",
                            HBCIUtils.LOG_DEBUG);
                } else {
                    HBCIUtils.log(
                            "have to generate new message because of BPD restrictions for number of tasks per message; adding job to this new message",
                            HBCIUtils.LOG_DEBUG);
                }
                newMsg();
                gv_counter=1;
                total_counter=1;
            }

            listOfGVs.setProperty(hbciCode,Integer.toString(gv_counter));

            msgs.get(msgs.size()-1).add(job);
        } catch (Exception e) {
            String msg=HBCIUtilsInternal.getLocMsg("EXCMSG_CANTADDJOB",job.getName());
            if (!HBCIUtilsInternal.ignoreError(null,"client.errors.ignoreAddJobErrors",
                                       msg+": "+HBCIUtils.exception2String(e))) {
                throw new HBCI_Exception(msg,e);
            }
            
            HBCIUtils.log("task "+job.getName()+" will not be executed in current dialog",HBCIUtils.LOG_ERR);
        }
    }
    
    public List getAllTasks()
    {
        List tasks=new ArrayList();
        
        for (Iterator i=msgs.iterator();i.hasNext();) {
            tasks.addAll((List)i.next());
        }
        
        return tasks;
    }

    public void newMsg()
    {
        HBCIUtils.log("starting new message",HBCIUtils.LOG_DEBUG);
        msgs.add(new ArrayList<HBCIJobImpl>());
        listOfGVs.clear();
    }
    
    public List<ArrayList<HBCIJobImpl>> getMessages()
    {
        return this.msgs;
    }
}
