package BIDMach.datasources
import BIDMat.{Mat,SBMat,CMat,CSMat,DMat,FMat,IMat,HMat,GMat,GIMat,GSMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import scala.concurrent.future
import scala.concurrent.ExecutionContextExecutor
import java.io._

class FilesDS(override val opts:FilesDS.Opts = new FilesDS.Options)(implicit val ec:ExecutionContextExecutor) extends DataSource(opts) { 
  var sizeMargin = 0f
  var blockSize = 0 
  @volatile var fileno = 0
  var rowno = 0
  var nstart = 0
  var fnames:List[(Int)=>String] = null
  omats = null
  var matqueue:Array[Array[Mat]] = null
  var ready:IMat = null
  var stop:Boolean = false
  var permfn:(Int)=>Int = null
  var totalSize = 0
  var fprogress:Float = 0
  
  def softperm(nstart:Int, nend:Int) = {
    val dd1 = nstart / 24
    val hh1 = nstart % 24
    val dd2 = nend / 24
    val hh2 = nend % 24
    val (dmy, ii) = sort2(rand(dd2-dd1+1+opts.lookahead))
    (n:Int) => {
    	val dd = n / 24
    	val hh = n % 24
    	val ddx = ii(dd-dd1)+dd1
    	val ddx0 = ddx % 31
    	val ddx1 = ddx / 31
    	val hhdd = hh + 24 * (ddx0 - 1)
    	(ddx1 * 31 + (hhdd % 31 + 1)) * 24 + hhdd / 31
    }    
  }
  
  def genperm(nstart:Int, nend:Int) = {
    val (dmy, ii) = sort2(rand(nend - nstart - 1,1));
    (n:Int) => {
      if (n >= nend - 1) {
        n
      } else {
        nstart + ii(n - nstart, 0);
      }
    }
  }
  
  def initbase = {
    reset    
    rowno = 0;
    fileno = nstart;                                                            // Number of the current output file                                                                 // row number in the current output file
    totalSize = opts.nend - nstart
    matqueue = new Array[Array[Mat]](math.max(1,opts.lookahead))               // Queue of matrices for each output matrix
    for (i <- 0 until math.max(1,opts.lookahead)) {
      matqueue(i) = new Array[Mat](fnames.size)
    }
    for (i <- 0 until opts.lookahead) {
      future {
        prefetch(nstart + i)
      }
    }
  }
  
  def reset = {
    nstart = opts.nstart
    fnames = opts.fnames
    blockSize = opts.batchSize
    while (!fileExists(fnames(0)(nstart)) && nstart < opts.nend) {nstart += 1}
    if (nstart == opts.nend) {
      throw new RuntimeException("Couldnt find any files");
    }
    if (opts.order == 0) {
      permfn = (a:Int) => a
    } else if (opts.order == 1) {
      permfn = genperm(nstart, opts.nend)
    } else {
      permfn = (n:Int) => {                                                    // Stripe reads across disks (different days)
        val (yy, mm, dd, hh) = FilesDS.decodeDate(n)
        val hhdd = hh + 24 * (dd - 1)
        FilesDS.encodeDate(yy, mm, hhdd % 31 + 1, hhdd / 31)
      } 
    }
    rowno = 0;
    fileno = nstart;
    ready = -iones(math.max(opts.lookahead,1), 1)                              // Numbers of files currently loaded in queue
    for (i <- 0 until math.max(1,opts.lookahead)) {
      val ifile = nstart + i
      val ifilex = ifile % math.max(opts.lookahead, 1)
      ready(ifilex) = ifile - math.max(1, opts.lookahead)
    } 
  }
  
  def init = {
    initbase
    omats = new Array[Mat](fnames.size)
    for (i <- 0 until fnames.size) {
      var mm = HMat.loadMat(fnames(i)(nstart));
      val (nr, nc) = if (opts.dorows) (blockSize, mm.ncols) else (mm.nrows, blockSize);
      omats(i) = mm match {
        case mf:FMat => zeros(nr, nc);
        case mi:IMat => izeros(nr, nc);
        case md:DMat => dzeros(nr, nc);
      }
    } 
  }
  
  def progress = {
    ((fileno-nstart)*1f + fprogress)/ totalSize
  }
  
  def nmats = omats.length
  
  def next:Array[Mat] = {
    var donextfile = false;
    var todo = blockSize;
    val featType = opts.featType;
    val threshold = opts.featThreshold;
    while (todo > 0 && fileno < opts.nend) {
    	var nrow = rowno;
    	val filex = fileno % math.max(1, opts.lookahead);
    	if (opts.lookahead > 0) {
    	  while (ready(filex) < fileno) Thread.`yield`
    	} else {
    	  fetch
    	}
    	var matqnr = 0
    	for (i <- 0 until fnames.size) {
    		val matq = matqueue(filex)(i);
    		if (matq.asInstanceOf[AnyRef] != null) {
    		  matqnr = if (opts.dorows) matq.nrows else matq.ncols;
    		  nrow = math.min(rowno + todo, matqnr);
    		  if (opts.dorows) {
    		    omats(i) = matq.rowslice(rowno, nrow, omats(i), blockSize - todo); 			  
    		  } else {
    		    omats(i) = matq.colslice(rowno, nrow, omats(i), blockSize - todo);			  
    		  }
    		  if (featType == 0) {
    		    min(1f, omats(i), omats(i));
    		  } else if (featType == 2) {
    		    omats(i) ~ omats(i) >= threshold;
    		  }
    		  if (matqnr == nrow) donextfile = true;
    		} else {
    		  if (opts.throwMissing) {
    		    throw new RuntimeException("Missing file "+fileno);
    		  }
    		  donextfile = true;
    		}
    	}
    	todo -= nrow - rowno;
    	if (donextfile) {
    	  rowno = 0;
    	  fileno += 1;
    	  donextfile = false;
    	} else {
    	  rowno = nrow;
    	}
    	fprogress = rowno*1f / matqnr;
    }
    omats
  }
  
  def fileExists(fname:String) = {
    val testme = new File(fname)
    testme.exists
  }
  
  def lazyTranspose(a:Mat) = {
    a match {
      case af:FMat => FMat(a.ncols, a.nrows, af.data)
      case ad:DMat => DMat(a.ncols, a.nrows, ad.data)
      case ai:IMat => IMat(a.ncols, a.nrows, ai.data)
      case _ => throw new RuntimeException("laztTranspose cant deal with "+a.getClass.getName)
    }
  }
  
  def prefetch(ifile:Int) = {
    val ifilex = ifile % opts.lookahead
  	ready(ifilex) = ifile - opts.lookahead
  	while  (!stop) {
      while (ready(ifilex) >= fileno && !stop) Thread.`yield`
      if (!stop) {
        val inew = ready(ifilex) + opts.lookahead;
        val pnew = permfn(inew);
        val fexists = fileExists(fnames(0)(pnew)) && (rand(1,1).v <= opts.sampleFiles);
        for (i <- 0 until fnames.size) {
          matqueue(ifilex)(i) = if (fexists) {
            HMat.loadMat(fnames(i)(pnew), matqueue(ifilex)(i));	 
          } else {
            if (opts.throwMissing && inew < opts.nend) {
              throw new RuntimeException("Missing file "+fnames(i)(pnew));
            }
            null;  	
          }
          //  			println("%d" format inew)
        }
        ready(ifilex) = inew;
      }
  	}
  }
  
  def fetch = {
    if (ready(0) < fileno) {
      val pnew = permfn(fileno);
      val fexists = fileExists(fnames(0)(pnew)) && (rand(1,1).v <= opts.sampleFiles);
      for (i <- 0 until fnames.size) {
        matqueue(0)(i) = if (fexists) {
          HMat.loadMat(fnames(i)(pnew), matqueue(0)(i));  
        } else {
          if (opts.throwMissing) {
            throw new RuntimeException("Missing file "+fnames(i)(pnew));
          }
          null;              
        }
      }
      ready(0) = fileno;
    }
  }

  
  def hasNext:Boolean = {
    (fileno < opts.nend)
  }

  override def close = {
//    stop = true
  }
}


object FilesDS {
  
  def encodeDate(yy:Int, mm:Int, dd:Int, hh:Int) = (((12*yy + mm) * 31) + dd)*24 + hh
  
  def decodeDate(n:Int):(Int, Int, Int, Int) = {
    val days = n / 24
    val dd = (days - 1) % 31 + 1
    val months = (days - dd) / 31
    val mm = (months - 1) % 12 + 1
    val yy = (months - mm) / 12
    (yy, mm, dd, n % 24)
  }
  
  def sampleFun(fname:String):(Int)=>String = {
    (n:Int) => {    
    	val (yy, mm, dd, hh) = decodeDate(n)
    	(fname format ((n / 24) % 16, yy, mm, dd, hh))
    }    
  }
  
  def sampleFun(fname:String, m:Int, i:Int):(Int)=>String = {
    (n0:Int) => { 
      val n = n0 * m + i
    	val (yy, mm, dd, hh) = decodeDate(n)
    	(fname format ((n / 24) % 16, yy, mm, dd, hh))
    }    
  }
  
  def simpleEnum(fname:String, m:Int, i:Int):(Int)=>String = {
    (n0:Int) => { 
      val n = n0 * m + i
      (fname format n)
    }    
  }
 
  trait Opts extends DataSource.Opts {
  	val localDir:String = ""
  	var fnames:List[(Int)=>String] = null
  	var lookahead = 2
  	var sampleFiles = 1.0f
    var nstart:Int = 0
    var nend:Int = 0
    var dorows:Boolean = false
    var order:Int = 0                          // 0 = sequential order, 1 = random
    var throwMissing:Boolean = false
  }
  
  class Options extends Opts {}
}
