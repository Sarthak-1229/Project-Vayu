import os
import sys
import subprocess

try:
    from PIL import Image
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "Pillow"])
    from PIL import Image

def resize_icon():
    base_dir = r"d:\TECH_&_STUFF\Project Vayu"
    src_img = os.path.join(base_dir, "logo.png")
    res_dir = os.path.join(base_dir, "app", "src", "main", "res")
    
    if not os.path.exists(src_img):
        print("logo.png not found!")
        return

    img = Image.open(src_img).convert("RGBA")
    
    # Standard and Round Icons
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    
    # Adaptive Foreground Icons
    adaptive_sizes = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }

    for folder, size in sizes.items():
        folder_path = os.path.join(res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        resized.save(os.path.join(folder_path, "ic_launcher.png"), "PNG")
        resized.save(os.path.join(folder_path, "ic_launcher_round.png"), "PNG")

    for folder, size in adaptive_sizes.items():
        folder_path = os.path.join(res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)
        # Create a padded foreground (app icon inside adaptive boundaries)
        # Adaptive icon safe zone is middle 66 diameter. 
        # So we scale the image to 66% of the target size and paste it in center.
        fg = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        logo_size = int(size * 0.6)
        logo_resized = img.resize((logo_size, logo_size), Image.Resampling.LANCZOS)
        offset = ((size - logo_size) // 2, (size - logo_size) // 2)
        fg.paste(logo_resized, offset, logo_resized)
        
        fg.save(os.path.join(folder_path, "ic_launcher_foreground.png"), "PNG")

    print("Icons generated successfully!")

if __name__ == "__main__":
    resize_icon()
